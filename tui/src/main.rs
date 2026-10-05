//! The `leia` binary: parses the command line and runs the terminal UI.

use anyhow::Result;
use clap::Parser;
use leia::cli::Cli;
use ratatui::crossterm::event::{self, Event, KeyCode, KeyEventKind};
use ratatui::layout::{Constraint, Layout};
use ratatui::text::Line;
use ratatui::widgets::{Block, Paragraph};
use ratatui::{DefaultTerminal, Frame};

fn main() -> Result<()> {
    let cli = Cli::parse();
    ratatui::run(|terminal| run(terminal, &cli))
}

fn run(terminal: &mut DefaultTerminal, cli: &Cli) -> Result<()> {
    loop {
        terminal.draw(|frame| draw(frame, cli))?;
        if let Event::Key(key) = event::read()?
            && key.kind == KeyEventKind::Press
            && matches!(key.code, KeyCode::Char('q') | KeyCode::Esc)
        {
            return Ok(());
        }
    }
}

fn draw(frame: &mut Frame, cli: &Cli) {
    let [body, footer] =
        Layout::vertical([Constraint::Fill(1), Constraint::Length(1)]).areas(frame.area());
    let index = match &cli.index_path {
        Some(path) => format!("Index: {}", path.display()),
        None => "No index given".to_owned(),
    };
    frame.render_widget(
        Paragraph::new(index).block(Block::bordered().title(" leia ")),
        body,
    );
    frame.render_widget(Line::from(" q quit"), footer);
}
