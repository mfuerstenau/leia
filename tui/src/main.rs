//! The `leia` binary: parses the command line, starts the server and runs the terminal UI.

use std::env;
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::time::Duration;

use anyhow::{Context, Result};
use clap::Parser;
use leia::app::{Action, App, Poll};
use leia::build_info::{LEIA, java_line, server_line};
use leia::cli::Cli;
use leia::client::CallError;
use leia::clipboard::osc52;
use leia::doc_export;
use leia::doc_filter::FilterStep;
use leia::history::{self, History};
use leia::jars::{self, DefaultJars};
use leia::java::find_java;
use leia::protocol::ErrorCode;
use leia::protocol::analysis::{Analyze, GetAnalysisComponents, GetAnalysisComponentsParams};
use leia::protocol::check::{
    GetCheckIndex, GetCheckIndexParams, StartCheckIndex, StartCheckIndexParams,
};
use leia::protocol::commits::{GetCommit, GetCommitParams, GetCommits, GetCommitsParams};
use leia::protocol::document::{
    GetDocument, GetDocumentParams, GetReconstruct, GetStoredValue, GetStoredValueParams,
    GetTermVector, GetTermVectorParams, StartReconstruct,
};
use leia::protocol::filter::{
    GetFilter, ReleaseFilter, ReleaseFilterParams, StartFilter, StepFilter, StepFilterParams,
};
use leia::protocol::index::{
    CloseIndex, CloseIndexParams, DirectoryImpl, GetIndexStats, GetIndexStatsParams, OpenIndex,
    OpenIndexParams, OpenMode, ReopenIndex, ReopenIndexParams,
};
use leia::protocol::jars::{AddJars, GetJars, GetJarsParams};
use leia::protocol::job::{CancelJob, JobParams};
use leia::protocol::search::{
    GetExplain, GetSearch, GetStoredScan, GetStoredScanParams, StartExplain, StartSearch,
    StartStoredScan,
};
use leia::protocol::terms::{
    GetExportTerms, GetFieldTermScan, GetPositions, GetPositionsParams, GetPostings,
    GetPostingsParams, GetTerms, GetTermsParams, StartExportTerms, StartFieldTermScan,
    StartFieldTermScanParams,
};
use leia::protocol::version::{Version, VersionParams, VersionResult};
use leia::server::{SERVER_JAR_ENV, SHUTDOWN_TIMEOUT, Server, check_jars, find_server_jar};
use leia::terms::{POSTINGS_PAGE, TERMS_PAGE};
use ratatui::DefaultTerminal;
use ratatui::crossterm::event::{self, Event, KeyEventKind};

fn main() -> Result<()> {
    let cli = Cli::parse();
    if cli.version {
        return print_version(&cli);
    }
    let index_path = cli.index_path.as_deref().map(utf8_path).transpose()?;
    check_jars(&cli.jars)?;
    let home = env::home_dir();
    // The default folder's jars come after those of `--jar`, which win a clash of names
    // (ADR 0024).
    let default_jars = DefaultJars::find(jars::default_dir(home.as_deref()), cli.no_default_jars);
    let mut server_jars = cli.jars.clone();
    server_jars.extend(default_jars.loaded_dir().map(Path::to_path_buf));

    // Start the server before taking over the terminal, so that startup errors are printed
    // normally.
    let (mut server, version) = start_server(&cli, &server_jars, default_jars.loaded_dir())?;
    let history =
        history::default_file(home.as_deref()).map_or_else(History::in_memory, History::load);
    let mut app = App::new(version, home)
        .with_default_jars(default_jars)
        .with_history(history)
        .with_directory(cli.directory)
        .with_cwd(env::current_dir().ok());

    // ratatui::run restores the terminal on exit and on panic; dropping the server on a panic
    // shuts it down too.
    let mode = if cli.no_reader {
        OpenMode::DirectoryOnly
    } else {
        OpenMode::Reader
    };
    let result = ratatui::run(|terminal| run(terminal, &mut server, &mut app, index_path, mode));
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
/// The jars don't matter to it, so the server starts without them.
fn print_version(cli: &Cli) -> Result<()> {
    let mut out = io::stdout().lock();
    writeln!(out, "{}", LEIA.line())?;
    out.flush()?;
    let (mut server, version) = start_server(cli, &[], None)?;
    writeln!(out, "{}", server_line(&version))?;
    writeln!(out, "{}", java_line(&version.java))?;
    server
        .shutdown(SHUTDOWN_TIMEOUT)
        .context("cannot stop leia-server")?;
    Ok(())
}

/// Finds the JVM and the jar, starts the server with the jars of `--jar` and the default folder
/// (`default_dir`, if its jars are loaded) and checks that it answers `version`.
fn start_server(
    cli: &Cli,
    jars: &[PathBuf],
    default_dir: Option<&Path>,
) -> Result<(Server, VersionResult)> {
    let java = find_java(cli.java.as_deref(), env::var_os("JAVA_HOME").as_deref())?;
    let jar = find_server_jar(env::var_os(SERVER_JAR_ENV).as_deref())?;
    let mut server = Server::spawn(&java.executable, &jar, jars)
        .with_context(|| format!("no usable JVM: {java}; choose one with --java or JAVA_HOME"))?;
    let version = server.call::<Version>(VersionParams {}).with_context(|| {
        match (jars.is_empty(), default_dir) {
            (true, _) => format!(
                "leia-server did not start with {java}; it needs Java 25 or newer (see --java)"
            ),
            (false, None) => format!(
                "leia-server did not start with {java}; it needs Java 25 or newer (see --java) \
                 and jars it can load (see --jar)"
            ),
            (false, Some(dir)) => format!(
                "leia-server did not start with {java}; it needs Java 25 or newer (see --java) \
                 and jars it can load (see --jar), including those in the default folder {} \
                 (see --no-default-jars)",
                dir.display()
            ),
        }
    })?;
    Ok((server, version))
}

/// How often a running job is polled while no key is pressed.
const POLL_INTERVAL: Duration = Duration::from_millis(100);
/// How often a job is polled at first: most searches take a few milliseconds.
const FIRST_POLL_INTERVAL: Duration = Duration::from_millis(10);
/// How many polls of a new job use [`FIRST_POLL_INTERVAL`].
const FIRST_POLLS: u32 = 10;

fn run(
    terminal: &mut DefaultTerminal,
    server: &mut Server,
    app: &mut App,
    index_path: Option<String>,
    mode: OpenMode,
) -> Result<()> {
    if let Some(path) = index_path {
        app.opening(&path, mode);
        terminal.draw(|frame| app.draw(frame))?;
        // The overview or, without a reader, the commits screen is shown at first.
        let mut next = open_index(server, app, &path, app.directory(), mode)?;
        while let Some(action) = next {
            next = perform(terminal, server, app, action)?;
        }
    }
    let mut polled: Vec<Poll> = Vec::new();
    let mut first_polls = 0;
    loop {
        terminal.draw(|frame| app.draw(frame))?;
        let polls = app.polls();
        if polls != polled {
            first_polls = FIRST_POLLS;
            polled.clone_from(&polls);
        }
        let interval = if first_polls > 0 {
            first_polls -= 1;
            FIRST_POLL_INTERVAL
        } else {
            POLL_INTERVAL
        };
        // Waits for a key only as long as the next poll of the jobs.
        let actions = if !polls.is_empty() && !event::poll(interval)? {
            poll_jobs(server, app, &polls)?
        } else {
            match event::read()? {
                // Windows reports both press and release; only handle presses.
                Event::Key(key) if key.kind == KeyEventKind::Press => {
                    app.handle_key(key).into_iter().collect()
                }
                _ => Vec::new(),
            }
        };
        for action in actions {
            // An action can lead to another one: a finished scan starts the next queued one.
            let mut next = Some(action);
            while let Some(action) = next {
                if action == Action::Quit {
                    return Ok(());
                }
                next = perform(terminal, server, app, action)?;
            }
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
        Action::Open {
            path,
            directory_impl,
            mode,
        } => {
            // Shows "Opening…" while the server works.
            terminal.draw(|frame| app.draw(frame))?;
            return open_index(server, app, &path, directory_impl, mode);
        }
        Action::Close => {
            server.call::<CloseIndex>(CloseIndexParams {})?;
            app.closed();
        }
        Action::Reopen => {
            // Shows "Reopening…" while the server works.
            terminal.draw(|frame| app.draw(frame))?;
            return reopen_index(server, app);
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
        Action::LoadDocument {
            doc_id,
            cancel,
            locate,
        } => {
            cancel_jobs(server, cancel)?;
            match answered(server.call::<GetDocument>(GetDocumentParams { doc_id }))? {
                Ok(document) => {
                    if let Some(filter_id) = locate {
                        let params = StepFilterParams {
                            filter_id,
                            from_doc: doc_id,
                            forward: true,
                        };
                        match answered(server.call::<StepFilter>(params))? {
                            Ok(result) => {
                                app.filter_stepped(params, FilterStep::Locate, result.found);
                            }
                            Err(error) => app.filter_failed(error),
                        }
                    }
                    return Ok(app.document_loaded(document));
                }
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
        Action::StartReconstruct { params, cancel } => {
            cancel_jobs(server, cancel)?;
            match answered(server.call::<StartReconstruct>(params))? {
                Ok(job) => app.reconstruct_started(job.job_id),
                Err(error) => app.reconstruct_failed(error),
            }
        }
        Action::CancelReconstruct(job_id) => {
            server.call::<CancelJob>(JobParams { job_id })?;
            app.reconstruct_cancelled();
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
        Action::LoadCommits(index) => {
            let params = GetCommitsParams {
                index: Some(position(index)),
            };
            match answered(server.call::<GetCommits>(params))? {
                Ok(result) => return Ok(app.commits_loaded(index, result)),
                Err(error) => app.commits_failed(index, error),
            }
        }
        Action::LoadCommit(index, generation) => {
            let params = GetCommitParams {
                generation,
                index: Some(position(index)),
            };
            match answered(server.call::<GetCommit>(params))? {
                Ok(result) => app.commit_loaded(index, generation, result),
                Err(error) => app.commit_failed(index, generation, error),
            }
        }
        Action::StartSearch { params, cancel } => {
            cancel_jobs(server, cancel)?;
            match answered(server.call::<StartSearch>(params))? {
                Ok(job) => app.search_started(job.job_id),
                Err(error) => app.search_failed(error),
            }
        }
        Action::StartExplain { params, cancel } => {
            cancel_jobs(server, cancel)?;
            match answered(server.call::<StartExplain>(params))? {
                Ok(job) => app.explain_started(job.job_id),
                Err(error) => app.explain_failed(error),
            }
        }
        Action::StartStoredScan { params, cancel } => {
            cancel_jobs(server, cancel)?;
            match answered(server.call::<StartStoredScan>(params))? {
                Ok(job) => app.stored_scan_started(job.job_id),
                Err(error) => app.stored_scan_failed(error),
            }
        }
        Action::StartFilter {
            params,
            cancel,
            release,
        } => {
            clear_filter(server, cancel, release)?;
            match answered(server.call::<StartFilter>(params))? {
                Ok(job) => app.filter_started(job.job_id),
                Err(error) => app.filter_failed(error),
            }
        }
        Action::StepFilter { params, step } => match answered(server.call::<StepFilter>(params))? {
            Ok(result) => return Ok(app.filter_stepped(params, step, result.found)),
            Err(error) => app.filter_failed(error),
        },
        Action::ClearFilter { cancel, release } => clear_filter(server, cancel, release)?,
        Action::CancelSearch(jobs) => {
            cancel_jobs(server, jobs)?;
            app.search_cancelled();
        }
        Action::LoadAnalysisComponents => {
            let params = GetAnalysisComponentsParams {};
            match answered(server.call::<GetAnalysisComponents>(params))? {
                Ok(result) => app.components_loaded(result),
                Err(error) => app.components_failed(error),
            }
        }
        Action::Analyze(params) => {
            let text = params.text.clone();
            return Ok(match answered(server.call::<Analyze>(params))? {
                Ok(result) => app.analyzed(text, result),
                Err(error) => app.analyze_failed(error),
            });
        }
        Action::StartCheck(level) => {
            let params = StartCheckIndexParams { level: Some(level) };
            match answered(server.call::<StartCheckIndex>(params))? {
                Ok(job) => app.check_started(job.job_id),
                Err(error) => app.check_failed(error),
            }
        }
        Action::CancelCheck(job_id) => {
            server.call::<CancelJob>(JobParams { job_id })?;
            app.check_cancelled();
        }
        Action::StartExport(params) => {
            // Shows "Exporting…" while the server checks the path.
            terminal.draw(|frame| app.draw(frame))?;
            match answered(server.call::<StartExportTerms>(params))? {
                Ok(job) => app.export_started(job.job_id),
                Err(error) => app.export_failed(error),
            }
        }
        Action::CancelExport(job_id) => {
            server.call::<CancelJob>(JobParams { job_id })?;
            app.export_cancelled();
        }
        Action::LoadDocumentExport {
            doc_id,
            indexed_terms,
            target,
        } => {
            // Shows "Exporting…" while the document is fetched.
            terminal.draw(|frame| app.draw(frame))?;
            match answered(doc_export::load(server, doc_id))? {
                Ok(data) => return Ok(app.document_export_loaded(data, indexed_terms, target)),
                Err(error) => app.export_failed(error),
            }
        }
        Action::StartDocumentExport(params) => {
            match answered(server.call::<StartReconstruct>(params))? {
                Ok(job) => app.export_started(job.job_id),
                Err(error) => app.export_failed(error),
            }
        }
        Action::WriteExport {
            doc_id,
            path,
            text,
            overwrite,
            index_dirs,
        } => match doc_export::write_file(Path::new(&path), &text, overwrite, &index_dirs) {
            Ok(()) => app.document_exported(doc_id, &path),
            Err(error) => app.export_failed(error),
        },
        Action::LoadJars => match answered(server.call::<GetJars>(GetJarsParams {}))? {
            Ok(result) => app.jars_loaded(result.jars),
            Err(error) => app.jars_failed(error),
        },
        Action::AddJars(params) => {
            // Shows "Loading…" while the server loads the jars.
            terminal.draw(|frame| app.draw(frame))?;
            match answered(server.call::<AddJars>(params))? {
                Ok(result) => return Ok(app.jars_added(result)),
                Err(error) => app.add_jars_failed(error),
            }
        }
    }
    Ok(None)
}

fn cancel_jobs(server: &mut Server, jobs: Vec<i64>) -> Result<()> {
    for job_id in jobs {
        server.call::<CancelJob>(JobParams { job_id })?;
    }
    Ok(())
}

/// Fetches the progress or the outcome of each running job. Returns the actions that follow.
fn poll_jobs(server: &mut Server, app: &mut App, polls: &[Poll]) -> Result<Vec<Action>> {
    let mut actions = Vec::new();
    for &poll in polls {
        let next = match poll {
            Poll::Scan(job_id) => {
                match answered(server.call::<GetFieldTermScan>(JobParams { job_id }))? {
                    Ok(result) => app.scan_progress(result),
                    Err(error) => app.scan_failed(error),
                }
            }
            Poll::Search(job_id) => {
                match answered(server.call::<GetSearch>(JobParams { job_id }))? {
                    Ok(result) => app.search_progress(result),
                    Err(error) => {
                        app.search_failed(error);
                        None
                    }
                }
            }
            Poll::Explain(job_id) => {
                match answered(server.call::<GetExplain>(JobParams { job_id }))? {
                    Ok(result) => app.explain_progress(result),
                    Err(error) => app.explain_failed(error),
                }
                None
            }
            Poll::StoredScan(job_id) => {
                let params = GetStoredScanParams {
                    job_id,
                    from_hit: app.stored_scan_from_hit(),
                };
                match answered(server.call::<GetStoredScan>(params))? {
                    Ok(result) => app.stored_scan_progress(result),
                    Err(error) => app.stored_scan_failed(error),
                }
                None
            }
            Poll::Check(job_id) => {
                let params = GetCheckIndexParams {
                    job_id,
                    from_line: app.check_from_line(),
                };
                match answered(server.call::<GetCheckIndex>(params))? {
                    Ok(result) => app.check_progress(result),
                    Err(error) => app.check_failed(error),
                }
                None
            }
            Poll::Filter(job_id) => {
                match answered(server.call::<GetFilter>(JobParams { job_id }))? {
                    Ok(result) => app.filter_progress(result),
                    Err(error) => {
                        app.filter_failed(error);
                        None
                    }
                }
            }
            Poll::Reconstruct(job_id) => {
                match answered(server.call::<GetReconstruct>(JobParams { job_id }))? {
                    Ok(result) => app.reconstruct_progress(result),
                    Err(error) => app.reconstruct_failed(error),
                }
                None
            }
            Poll::DocumentExport(job_id) => {
                match answered(server.call::<GetReconstruct>(JobParams { job_id }))? {
                    Ok(result) => app.document_export_progress(result),
                    Err(error) => {
                        app.export_failed(error);
                        None
                    }
                }
            }
            Poll::Export(job_id) => {
                match answered(server.call::<GetExportTerms>(JobParams { job_id }))? {
                    Ok(result) => app.export_progress(result),
                    Err(error) => app.export_failed(error),
                }
                None
            }
        };
        actions.extend(next);
    }
    Ok(actions)
}

/// Opens the index and, with a reader, fetches its statistics for the overview. The server's
/// errors are shown in the UI; a broken connection to the server ends leia. Returns the action
/// that follows, if any: what the shown screen has to fetch first.
fn open_index(
    server: &mut Server,
    app: &mut App,
    path: &str,
    directory_impl: DirectoryImpl,
    mode: OpenMode,
) -> Result<Option<Action>> {
    let opened = server.call::<OpenIndex>(OpenIndexParams {
        path: path.to_owned(),
        directory_impl: Some(directory_impl),
        mode: Some(mode),
    });
    let result = match opened.map_err(with_jar_hint) {
        Ok(result) => result,
        Err(CallError::Rpc(error)) => {
            // A reader can't open a corrupt index, but its commits may still be readable.
            let no_reader_helps = matches!(
                error.code,
                ErrorCode::CorruptIndex | ErrorCode::MissingCodec
            );
            app.open_failed(CallError::Rpc(error).to_string(), no_reader_helps);
            return Ok(None);
        }
        Err(CallError::Transport(error)) => return Err(error),
    };
    if result.mode == OpenMode::DirectoryOnly {
        return Ok(app.opened_without_reader(result));
    }
    match answered(server.call::<GetIndexStats>(GetIndexStatsParams {}))? {
        Ok(stats) => Ok(app.opened(stats, result.skipped)),
        Err(error) => {
            // Opened, but the overview can't show it: close it rather than keep it open unseen.
            server.call::<CloseIndex>(CloseIndexParams {})?;
            app.closed();
            app.open_failed(format!("cannot read the index statistics: {error}"), false);
            Ok(None)
        }
    }
}

/// The position of an index directory on the wire.
fn position(index: usize) -> i32 {
    i32::try_from(index).unwrap_or(i32::MAX)
}

/// Reopens the index if it changed, and fetches its statistics for the overview (ADR 0019). A
/// failed reopen leaves the open index open, and only says why. Returns the action that follows,
/// if any: what the shown screen has to fetch first.
fn reopen_index(server: &mut Server, app: &mut App) -> Result<Option<Action>> {
    let reopened = server.call::<ReopenIndex>(ReopenIndexParams {});
    let result = match answered(reopened.map_err(with_jar_hint))? {
        Ok(result) => result,
        Err(error) => {
            app.reopen_failed(error);
            return Ok(None);
        }
    };
    if !result.changed {
        app.reopen_unchanged(&result);
        return Ok(None);
    }
    match answered(server.call::<GetIndexStats>(GetIndexStatsParams {}))? {
        Ok(stats) => Ok(app.reopened(stats)),
        Err(error) => {
            // Reopened, but the overview can't show it: close it rather than keep it open unseen.
            server.call::<CloseIndex>(CloseIndexParams {})?;
            app.closed();
            app.open_failed(format!("cannot read the index statistics: {error}"), false);
            Ok(None)
        }
    }
}

/// A codec or format that isn't loaded can come from a jar that X adds or leia is started with
/// (ADR 0018, ADR 0024).
fn with_jar_hint(error: CallError) -> CallError {
    match error {
        CallError::Rpc(mut error) if error.code == ErrorCode::MissingCodec => {
            error
                .message
                .push_str("; add a jar that has it with X, or start leia with --jar");
            CallError::Rpc(error)
        }
        error => error,
    }
}

/// Splits the outcome of a call: an error the server answered with becomes a message for the UI,
/// a transport error is returned as an error.
/// Stops building a filter, or forgets the one the server keeps (ADR 0031).
fn clear_filter(server: &mut Server, cancel: Option<i64>, release: Option<i64>) -> Result<()> {
    if let Some(job_id) = cancel {
        server.call::<CancelJob>(JobParams { job_id })?;
    }
    if let Some(filter_id) = release {
        server.call::<ReleaseFilter>(ReleaseFilterParams { filter_id })?;
    }
    Ok(())
}

fn answered<T>(result: Result<T, CallError>) -> Result<Result<T, String>> {
    match result {
        Ok(value) => Ok(Ok(value)),
        Err(error @ CallError::Rpc(_)) => Ok(Err(error.to_string())),
        Err(CallError::Transport(error)) => Err(error),
    }
}

#[cfg(test)]
mod tests {
    use leia::protocol::RpcError;

    use super::*;

    fn rpc_error(code: ErrorCode, message: &str) -> CallError {
        CallError::Rpc(RpcError {
            code,
            message: message.to_owned(),
        })
    }

    #[test]
    fn a_missing_codec_hints_at_jar() {
        let error = with_jar_hint(rpc_error(
            ErrorCode::MissingCodec,
            "Missing codec: the index needs the codec 'X', which isn't loaded",
        ));

        assert_eq!(
            error.to_string(),
            "Missing codec: the index needs the codec 'X', which isn't loaded; add a jar that \
             has it with X, or start leia with --jar (MISSING_CODEC -32014)"
        );
    }

    #[test]
    fn other_errors_get_no_hint() {
        let error = with_jar_hint(rpc_error(ErrorCode::CorruptIndex, "Corrupt index: x"));

        assert_eq!(error.to_string(), "Corrupt index: x (CORRUPT_INDEX -32003)");
    }
}
