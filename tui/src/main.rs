//! The `leia` binary: parses the command line, starts the server and runs the terminal UI.

use std::env;
use std::io::{self, Write};
use std::path::Path;
use std::time::Duration;

use anyhow::{Context, Result};
use clap::Parser;
use leia::app::{Action, App};
use leia::build_info::{LEIA, java_line, server_line};
use leia::cli::Cli;
use leia::client::CallError;
use leia::clipboard::osc52;
use leia::java::find_java;
use leia::protocol::document::{
    GetDocument, GetDocumentParams, GetStoredValue, GetStoredValueParams, GetTermVector,
    GetTermVectorParams,
};
use leia::protocol::index::{
    CloseIndex, CloseIndexParams, GetIndexStats, GetIndexStatsParams, OpenIndex, OpenIndexParams,
};
use leia::protocol::job::{CancelJob, JobParams};
use leia::protocol::terms::{
    GetFieldTermScan, GetPositions, GetPositionsParams, GetPostings, GetPostingsParams, GetTerms,
    GetTermsParams, StartFieldTermScan, StartFieldTermScanParams,
};
use leia::protocol::version::{Version, VersionParams, VersionResult};
use leia::server::{SERVER_JAR_ENV, SHUTDOWN_TIMEOUT, Server, find_server_jar};
use leia::terms::{POSTINGS_PAGE, TERMS_PAGE};
use ratatui::DefaultTerminal;
use ratatui::crossterm::event::{self, Event, KeyEventKind};

fn main() -> Result<()> {
    let cli = Cli::parse();
    if cli.version {
        return print_version(&cli);
    }
    let index_path = cli.index_path.as_deref().map(utf8_path).transpose()?;

    // Start the server before taking over the terminal, so that startup errors are printed
    // normally.
    let (mut server, version) = start_server(&cli)?;
    let mut app = App::new(version, env::home_dir());

    // ratatui::run restores the terminal on exit and on panic; dropping the server on a panic
    // shuts it down too.
    let result = ratatui::run(|terminal| run(terminal, &mut server, &mut app, index_path));
    let closed = if app.has_index() {
        server
            .call::<CloseIndex>(CloseIndexParams {})
            .map(drop)
            .context("cannot close the index")
    } else {
        Ok(())
    };
    let stopped = server
        .shutdown(SHUTDOWN_TIMEOUT)
        .context("cannot stop leia-server");
    result?;
    closed?;
    stopped?;
    Ok(())
}

/// The protocol carries paths as strings (JSON has no other way), so they must be UTF-8.
fn utf8_path(path: &Path) -> Result<String> {
    path.to_str()
        .map(str::to_owned)
        .with_context(|| format!("the index path is not valid UTF-8: {}", path.display()))
}

/// `--version`: leia's own line comes first, so it is printed even if the server can't start.
fn print_version(cli: &Cli) -> Result<()> {
    let mut out = io::stdout().lock();
    writeln!(out, "{}", LEIA.line())?;
    out.flush()?;
    let (mut server, version) = start_server(cli)?;
    writeln!(out, "{}", server_line(&version))?;
    writeln!(out, "{}", java_line(&version.java))?;
    server
        .shutdown(SHUTDOWN_TIMEOUT)
        .context("cannot stop leia-server")?;
    Ok(())
}

/// Finds the JVM and the jar, starts the server and checks that it answers `version`.
fn start_server(cli: &Cli) -> Result<(Server, VersionResult)> {
    let java = find_java(cli.java.as_deref(), env::var_os("JAVA_HOME").as_deref())?;
    let jar = find_server_jar(env::var_os(SERVER_JAR_ENV).as_deref())?;
    let mut server = Server::spawn(&java.executable, &jar)
        .with_context(|| format!("no usable JVM: {java}; choose one with --java or JAVA_HOME"))?;
    let version = server.call::<Version>(VersionParams {}).with_context(|| {
        format!("leia-server did not start with {java}; it needs Java 25 or newer (see --java)")
    })?;
    Ok((server, version))
}

/// How often a running job is polled while no key is pressed.
const POLL_INTERVAL: Duration = Duration::from_millis(100);

fn run(
    terminal: &mut DefaultTerminal,
    server: &mut Server,
    app: &mut App,
    index_path: Option<String>,
) -> Result<()> {
    if let Some(path) = index_path {
        app.opening(&path);
        terminal.draw(|frame| app.draw(frame))?;
        // The overview is shown at first, so no action follows.
        open_index(server, app, &path)?;
    }
    loop {
        terminal.draw(|frame| app.draw(frame))?;
        let mut next = match app.polling() {
            // Waits for a key only as long as the next poll of the job.
            Some(job_id) if !event::poll(POLL_INTERVAL)? => poll_scan(server, app, job_id)?,
            _ => match event::read()? {
                // Windows reports both press and release; only handle presses.
                Event::Key(key) if key.kind == KeyEventKind::Press => app.handle_key(key),
                _ => None,
            },
        };
        // An action can lead to another one: a finished scan starts the next queued one.
        while let Some(action) = next {
            if action == Action::Quit {
                return Ok(());
            }
            next = perform(terminal, server, app, action)?;
        }
    }
}

/// Makes the calls for `action` and reports back to `app`. Returns the action that follows, if
/// any.
fn perform(
    terminal: &mut DefaultTerminal,
    server: &mut Server,
    app: &mut App,
    action: Action,
) -> Result<Option<Action>> {
    match action {
        Action::Quit => {}
        Action::Open(path) => {
            // Shows "Opening…" while the server works.
            terminal.draw(|frame| app.draw(frame))?;
            return open_index(server, app, &path);
        }
        Action::Close => {
            server.call::<CloseIndex>(CloseIndexParams {})?;
            app.closed();
        }
        Action::StartScan { field, top_n } => {
            let started =
                server.call::<StartFieldTermScan>(StartFieldTermScanParams { field, top_n });
            match answered(started)? {
                Ok(job) => app.scan_started(job.job_id),
                Err(error) => return Ok(app.scan_failed(error)),
            }
        }
        Action::CancelScan(job_id) => {
            server.call::<CancelJob>(JobParams { job_id })?;
            app.scan_cancelled();
        }
        Action::LoadDocument(doc_id) => {
            match answered(server.call::<GetDocument>(GetDocumentParams { doc_id }))? {
                Ok(document) => return Ok(app.document_loaded(document)),
                Err(error) => app.document_failed(doc_id, error),
            }
        }
        Action::LoadStoredValue {
            doc_id,
            field,
            index,
        } => {
            let params = GetStoredValueParams {
                doc_id,
                field: field.clone(),
                index,
            };
            match answered(server.call::<GetStoredValue>(params))? {
                Ok(result) => app.stored_value_loaded(doc_id, &field, index, &result.value),
                Err(error) => app.stored_value_failed(doc_id, &field, index, error),
            }
        }
        Action::Copy(text) => {
            // Between two frames, so the sequence can't land inside one.
            let mut out = io::stdout().lock();
            out.write_all(osc52(&text).as_bytes())?;
            out.flush()?;
        }
        Action::LoadTermVector { doc_id, field } => {
            let params = GetTermVectorParams {
                doc_id,
                field: field.clone(),
            };
            match answered(server.call::<GetTermVector>(params))? {
                Ok(result) => app.term_vector_loaded(doc_id, &field, result.terms),
                Err(error) => app.term_vector_failed(doc_id, &field, error),
            }
        }
        Action::LoadTerms { field, from, more } => {
            let params = GetTermsParams {
                field: field.clone(),
                from,
                limit: TERMS_PAGE,
            };
            return Ok(match answered(server.call::<GetTerms>(params))? {
                Ok(result) => app.terms_loaded(&field, more, result),
                Err(error) => app.terms_failed(&field, more, error),
            });
        }
        Action::LoadPostings {
            field,
            term,
            from_doc,
            more,
        } => {
            let params = GetPostingsParams {
                field: field.clone(),
                term: term.clone(),
                from_doc,
                limit: POSTINGS_PAGE,
            };
            match answered(server.call::<GetPostings>(params))? {
                Ok(result) => return Ok(app.postings_loaded(&field, &term, more, result)),
                Err(error) => app.postings_failed(&field, &term, more, error),
            }
        }
        Action::LoadPositions {
            doc_id,
            field,
            term,
        } => {
            let params = GetPositionsParams {
                doc_id,
                field: field.clone(),
                term: term.clone(),
            };
            match answered(server.call::<GetPositions>(params))? {
                Ok(result) => app.positions_loaded(doc_id, &field, &term, result.positions),
                Err(error) => app.positions_failed(doc_id, &field, &term, error),
            }
        }
    }
    Ok(None)
}

/// Fetches the progress or the outcome of the running scan.
fn poll_scan(server: &mut Server, app: &mut App, job_id: i64) -> Result<Option<Action>> {
    Ok(
        match answered(server.call::<GetFieldTermScan>(JobParams { job_id }))? {
            Ok(result) => app.scan_progress(result),
            Err(error) => app.scan_failed(error),
        },
    )
}

/// Opens the index and fetches its statistics for the overview. The server's errors are shown
/// in the UI; a broken connection to the server ends leia. Returns the action that follows, if
/// any: the first document, if the documents screen is shown.
fn open_index(server: &mut Server, app: &mut App, path: &str) -> Result<Option<Action>> {
    let opened = server.call::<OpenIndex>(OpenIndexParams {
        path: path.to_owned(),
    });
    if let Err(error) = answered(opened)? {
        app.open_failed(error);
        return Ok(None);
    }
    match answered(server.call::<GetIndexStats>(GetIndexStatsParams {}))? {
        Ok(stats) => Ok(app.opened(stats)),
        Err(error) => {
            // Opened, but the overview can't show it: close it rather than keep it open unseen.
            server.call::<CloseIndex>(CloseIndexParams {})?;
            app.closed();
            app.open_failed(format!("cannot read the index statistics: {error}"));
            Ok(None)
        }
    }
}

/// Splits the outcome of a call: an error the server answered with becomes a message for the UI,
/// a transport error is returned as an error.
fn answered<T>(result: Result<T, CallError>) -> Result<Result<T, String>> {
    match result {
        Ok(value) => Ok(Ok(value)),
        Err(error @ CallError::Rpc(_)) => Ok(Err(error.to_string())),
        Err(CallError::Transport(error)) => Err(error),
    }
}
