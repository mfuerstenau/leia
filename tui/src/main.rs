//! The `leia` binary: parses the command line, starts the server and runs the terminal UI.

use std::env;

use anyhow::{Context, Result};
use clap::Parser;
use leia::cli::Cli;
use leia::java::find_java;
use leia::protocol::version::{Version, VersionParams, VersionResult};
use leia::server::{SERVER_JAR_ENV, SHUTDOWN_TIMEOUT, Server, find_server_jar};
use ratatui::crossterm::event::{self, Event, KeyCode, KeyEventKind};
use ratatui::layout::{Constraint, Layout};
use ratatui::text::Line;
use ratatui::widgets::{Block, Paragraph};
use ratatui::{DefaultTerminal, Frame};

fn main() -> Result<()> {
    let cli = Cli::parse();
    let java = find_java(cli.java.as_deref(), env::var_os("JAVA_HOME").as_deref())?;
    let jar = find_server_jar(env::var_os(SERVER_JAR_ENV).as_deref())?;

    // Start the server and check it answers before taking over the terminal, so that startup
    // errors are printed normally.
    let mut server = Server::spawn(&java.executable, &jar)
        .with_context(|| format!("no usable JVM: {java}; choose one with --java or JAVA_HOME"))?;
    let version = server.call::<Version>(VersionParams {}).with_context(|| {
        format!("leia-server did not start with {java}; it needs Java 25 or newer (see --java)")
    })?;

    // ratatui::run restores the terminal on exit and on panic; dropping the server on a panic
    // shuts it down too.
    let result = ratatui::run(|terminal| run(terminal, &cli, &version));
    let stopped = server
        .shutdown(SHUTDOWN_TIMEOUT)
        .context("cannot stop leia-server");
    result?;
    stopped?;
    Ok(())
}

fn run(terminal: &mut DefaultTerminal, cli: &Cli, version: &VersionResult) -> Result<()> {
    loop {
        terminal.draw(|frame| draw(frame, cli, version))?;
        // Windows reports both press and release; only handle presses.
        if let Event::Key(key) = event::read()?
            && key.kind == KeyEventKind::Press
            && matches!(key.code, KeyCode::Char('q') | KeyCode::Esc)
        {
            return Ok(());
        }
    }
}

fn draw(frame: &mut Frame, cli: &Cli, version: &VersionResult) {
    let [body, footer] =
        Layout::vertical([Constraint::Fill(1), Constraint::Length(1)]).areas(frame.area());
    let index = match &cli.index_path {
        Some(path) => format!("Index:  {}", path.display()),
        None => "No index given".to_owned(),
    };
    let lines = vec![
        Line::from(index),
        Line::from(""),
        Line::from(format!(
            "Server: {} {} ({}, {})",
            version.name, version.version, version.commit, version.commit_time
        )),
        Line::from(format!(
            "Java:   {} ({}) in {}",
            version.java.version, version.java.vendor, version.java.home
        )),
    ];
    frame.render_widget(
        Paragraph::new(lines).block(Block::bordered().title(" leia ")),
        body,
    );
    frame.render_widget(Line::from(" q quit"), footer);
}
