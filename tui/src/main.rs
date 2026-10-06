//! The `leia` binary: parses the command line, starts the server and runs the terminal UI.

use std::env;
use std::io::{self, Write};
use std::path::Path;

use anyhow::{Context, Result};
use clap::Parser;
use leia::build_info::{LEIA, java_line, server_line};
use leia::cli::Cli;
use leia::java::find_java;
use leia::overview::Overview;
use leia::protocol::index::{
    GetIndexStats, GetIndexStatsParams, GetIndexStatsResult, OpenIndex, OpenIndexParams,
};
use leia::protocol::version::{Version, VersionParams, VersionResult};
use leia::server::{SERVER_JAR_ENV, SHUTDOWN_TIMEOUT, Server, find_server_jar};
use ratatui::crossterm::event::{self, Event, KeyCode, KeyEventKind};
use ratatui::layout::{Constraint, Layout};
use ratatui::text::Line;
use ratatui::widgets::{Block, Paragraph};
use ratatui::{DefaultTerminal, Frame};

fn main() -> Result<()> {
    let cli = Cli::parse();
    if cli.version {
        return print_version(&cli);
    }

    // Start the server before taking over the terminal, so that startup errors are printed
    // normally.
    let (mut server, version) = start_server(&cli)?;
    let overview = match &cli.index_path {
        Some(path) => Some(Overview::new(open_index(&mut server, path)?)),
        None => None,
    };

    // ratatui::run restores the terminal on exit and on panic; dropping the server on a panic
    // shuts it down too.
    let result = ratatui::run(|terminal| run(terminal, &version, overview));
    let stopped = server
        .shutdown(SHUTDOWN_TIMEOUT)
        .context("cannot stop leia-server");
    result?;
    stopped?;
    Ok(())
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

/// Opens the index and fetches its statistics for the overview.
fn open_index(server: &mut Server, path: &Path) -> Result<GetIndexStatsResult> {
    // The protocol carries paths as strings (JSON has no other way), so they must be UTF-8.
    let path_str = path
        .to_str()
        .with_context(|| format!("the index path is not valid UTF-8: {}", path.display()))?;
    server
        .call::<OpenIndex>(OpenIndexParams {
            path: path_str.to_owned(),
        })
        .with_context(|| format!("cannot open the index {}", path.display()))?;
    server
        .call::<GetIndexStats>(GetIndexStatsParams {})
        .with_context(|| format!("cannot read the statistics of {}", path.display()))
}

fn run(
    terminal: &mut DefaultTerminal,
    version: &VersionResult,
    mut overview: Option<Overview>,
) -> Result<()> {
    loop {
        terminal.draw(|frame| draw(frame, version, overview.as_mut()))?;
        // Windows reports both press and release; only handle presses.
        let Event::Key(key) = event::read()? else {
            continue;
        };
        if key.kind != KeyEventKind::Press {
            continue;
        }
        if matches!(key.code, KeyCode::Char('q') | KeyCode::Esc) {
            return Ok(());
        }
        if let Some(overview) = overview.as_mut() {
            overview.handle_key(key.code);
        }
    }
}

fn draw(frame: &mut Frame, version: &VersionResult, overview: Option<&mut Overview>) {
    let [body, footer] =
        Layout::vertical([Constraint::Fill(1), Constraint::Length(1)]).areas(frame.area());
    match overview {
        Some(overview) => {
            overview.render(frame, body);
            let keys = Line::from(" q quit   ↑↓ PgUp PgDn Home End  fields");
            let build = Line::from(format!("{} ", LEIA.line())).right_aligned();
            // The build only if it fits next to the keys.
            if keys.width() + 2 + build.width() <= usize::from(footer.width) {
                frame.render_widget(build, footer);
            }
            frame.render_widget(keys, footer);
        }
        None => {
            let lines = vec![
                Line::from("No index open"),
                Line::from(""),
                Line::from(LEIA.line()),
                Line::from(server_line(version)),
                Line::from(java_line(&version.java)),
            ];
            frame.render_widget(
                Paragraph::new(lines).block(Block::bordered().title(" leia ")),
                body,
            );
            frame.render_widget(Line::from(" q quit"), footer);
        }
    }
}
