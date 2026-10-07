//! The analysis screen (ADR 0014): the current analyzer, a text line, and what the analyzer makes
//! of the text, step by step: the text after each char filter, and the tokens after the tokenizer
//! and each token filter, with every attribute of the selected token. It works without an open
//! index. `b` builds the analyzer, which the search screen then parses and likes with.

use ratatui::Frame;
use ratatui::crossterm::event::{KeyCode, KeyEvent};
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Cell, List, ListItem, ListState, Paragraph, Row, Table, TableState, Wrap};

use crate::analyzer::{AnalyzerForm, AnalyzerSettings};
use crate::form::{FormEvent, dim};
use crate::input::{LineInput, word_start};
use crate::overview::{highlight, panel};
use crate::protocol::analysis::{
    AnalyzeParams, AnalyzeResult, GetAnalysisComponentsResult, MAX_TEXT_LENGTH, MAX_TOKENS, Token,
};
use crate::terms::moved;
use crate::text::escape_control;

/// Rows that PageUp and PageDown scroll the details.
const PAGE: u16 = 10;
/// From this width on, the token details stand next to the tokens; below it, under them.
const WIDE: u16 = 120;

/// What the caller has to fetch for the analysis screen.
#[derive(Debug, PartialEq)]
pub enum Request {
    /// Fetch what custom analyzers can be built from, and report back with
    /// [`Analysis::components_loaded`] or [`Analysis::components_failed`].
    Components,
    /// Analyze, and report back with [`Analysis::analyzed`] or [`Analysis::analyze_failed`].
    Analyze(AnalyzeParams),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Focus {
    Steps,
    Tokens,
    Details,
}

/// What an analysis showed.
#[derive(Debug)]
struct Shown {
    /// The text that was analyzed.
    text: String,
    result: AnalyzeResult,
}

/// A step of the analysis: the text after a char filter, or the tokens after a stage.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Step {
    CharFilter(usize),
    Stage(usize),
}

pub struct Analysis {
    settings: AnalyzerSettings,
    components: Option<Result<GetAnalysisComponentsResult, String>>,
    input: LineInput,
    editing: bool,
    form: Option<AnalyzerForm>,
    /// The analyzer that the form applied, while the server tries it.
    trying: Option<AnalyzerSettings>,
    /// The last analysis, or why it failed.
    outcome: Option<Result<Shown, String>>,
    focus: Focus,
    steps: ListState,
    tokens: TableState,
    /// The first line of the details panel that is shown.
    details_scroll: u16,
}

impl Default for Analysis {
    fn default() -> Self {
        Self::new()
    }
}

impl Analysis {
    pub fn new() -> Self {
        Self {
            settings: AnalyzerSettings::default(),
            components: None,
            input: LineInput::new(""),
            editing: false,
            form: None,
            trying: None,
            outcome: None,
            focus: Focus::Tokens,
            steps: ListState::default(),
            tokens: TableState::new(),
            details_scroll: 0,
        }
    }

    /// The current analyzer.
    pub fn settings(&self) -> &AnalyzerSettings {
        &self.settings
    }

    /// Whether the text line or the popup takes the keys, Esc included.
    pub fn prompting(&self) -> bool {
        self.editing || self.form.is_some()
    }

    /// The components, the first time the screen is shown.
    pub fn initial_request(&self) -> Option<Request> {
        self.components.is_none().then_some(Request::Components)
    }

    pub fn components_loaded(&mut self, components: GetAnalysisComponentsResult) {
        if let Some(form) = &mut self.form {
            form.set_components(components.clone());
        }
        self.components = Some(Ok(components));
    }

    pub fn components_failed(&mut self, error: String) {
        self.components = Some(Err(error));
    }

    /// Takes the analysis. An analyzer that the form applied becomes the current one, and the
    /// form closes.
    pub fn analyzed(&mut self, text: String, result: AnalyzeResult) {
        if let Some(settings) = self.trying.take() {
            self.settings = settings;
            self.form = None;
        }
        let steps = result.char_filters.len() + result.stages.len();
        // The last stage is what the analyzer makes of the text; a new analysis starts there.
        self.steps.select(steps.checked_sub(1));
        self.tokens = TableState::new();
        self.tokens.select(Some(0));
        self.details_scroll = 0;
        self.outcome = Some(Ok(Shown { text, result }));
    }

    /// Shows why the analysis failed: in the form if it tried an analyzer, which stays open to
    /// fix it.
    pub fn analyze_failed(&mut self, error: String) {
        match (self.trying.take(), &mut self.form) {
            (Some(_), Some(form)) => form.set_error(error),
            _ => self.outcome = Some(Err(error)),
        }
    }

    /// The analysis of the text line with `settings`.
    fn analyze(&self, settings: &AnalyzerSettings) -> Option<Request> {
        Some(Request::Analyze(AnalyzeParams {
            analyzer: settings.analyzer(),
            text: self.input.text().to_owned(),
        }))
    }

    /// Edits the text line while it is open (Enter analyzes, Esc closes it), or the analyzer
    /// popup (Enter tries the analyzer, which becomes the current one once the server took it).
    /// Otherwise `/` opens the line, `b` the popup, `r` analyzes again, and Tab switches between
    /// the steps, the tokens and the token's details.
    pub fn handle_key(&mut self, key: KeyEvent) -> Option<Request> {
        if let Some(form) = &mut self.form {
            return match form.handle_key(key) {
                FormEvent::None => None,
                FormEvent::Cancel => {
                    self.form = None;
                    None
                }
                FormEvent::Apply(settings) => {
                    let request = self.analyze(&settings);
                    self.trying = Some(settings);
                    request
                }
            };
        }
        if self.editing {
            match key.code {
                KeyCode::Enter => {
                    if too_long(self.input.text()) {
                        self.outcome = Some(Err(format!(
                            "The text is longer than {MAX_TEXT_LENGTH} characters."
                        )));
                        return None;
                    }
                    self.editing = false;
                    return self.analyze(&self.settings);
                }
                KeyCode::Esc => self.editing = false,
                _ => {
                    self.input.handle_key(key, word_start);
                }
            }
            return None;
        }
        match key.code {
            KeyCode::Char('/') => self.editing = true,
            KeyCode::Char('b') => {
                let components = self
                    .components
                    .as_ref()
                    .and_then(|components| components.as_ref().ok())
                    .cloned();
                self.form = Some(AnalyzerForm::new(&self.settings, components));
            }
            KeyCode::Char('r') if self.outcome.is_some() => return self.analyze(&self.settings),
            KeyCode::Tab => {
                self.focus = match self.focus {
                    Focus::Steps => Focus::Tokens,
                    Focus::Tokens => Focus::Details,
                    Focus::Details => Focus::Steps,
                };
            }
            KeyCode::BackTab => {
                self.focus = match self.focus {
                    Focus::Steps => Focus::Details,
                    Focus::Tokens => Focus::Steps,
                    Focus::Details => Focus::Tokens,
                };
            }
            code => match self.focus {
                Focus::Steps => {
                    let len = self.steps_len();
                    let step = moved(self.steps.selected().unwrap_or_default(), len, code)?;
                    if Some(step) != self.steps.selected() {
                        self.steps.select(Some(step));
                        self.details_scroll = 0;
                    }
                }
                Focus::Tokens => {
                    let len = self.stage_tokens()?.len();
                    let row = moved(self.tokens.selected().unwrap_or_default(), len, code)?;
                    if Some(row) != self.tokens.selected() {
                        self.tokens.select(Some(row));
                        self.details_scroll = 0;
                    }
                }
                Focus::Details => self.scroll_details(code),
            },
        }
        None
    }

    fn scroll_details(&mut self, key: KeyCode) {
        let scroll = &mut self.details_scroll;
        match key {
            KeyCode::Down | KeyCode::Char('j') => *scroll = scroll.saturating_add(1),
            KeyCode::Up | KeyCode::Char('k') => *scroll = scroll.saturating_sub(1),
            KeyCode::PageDown => *scroll = scroll.saturating_add(PAGE),
            KeyCode::PageUp => *scroll = scroll.saturating_sub(PAGE),
            KeyCode::Home | KeyCode::Char('g') => *scroll = 0,
            KeyCode::End | KeyCode::Char('G') => *scroll = u16::MAX,
            _ => {}
        }
    }

    fn shown(&self) -> Option<&Shown> {
        self.outcome.as_ref()?.as_ref().ok()
    }

    fn steps_len(&self) -> usize {
        self.shown().map_or(0, |shown| {
            shown.result.char_filters.len() + shown.result.stages.len()
        })
    }

    fn step(&self) -> Option<Step> {
        let shown = self.shown()?;
        let selected = self.steps.selected()?;
        let char_filters = shown.result.char_filters.len();
        if selected < char_filters {
            Some(Step::CharFilter(selected))
        } else {
            (selected - char_filters < shown.result.stages.len())
                .then_some(Step::Stage(selected - char_filters))
        }
    }

    /// The tokens of the selected stage; `None` if a char filter is selected.
    fn stage_tokens(&self) -> Option<&[Token]> {
        let Step::Stage(stage) = self.step()? else {
            return None;
        };
        Some(&self.shown()?.result.stages[stage].tokens)
    }

    fn selected_token(&self) -> Option<&Token> {
        let tokens = self.stage_tokens()?;
        tokens.get(self.tokens.selected()?.min(tokens.len().checked_sub(1)?))
    }

    /// The keys for the footer.
    pub fn keys(&self) -> &'static str {
        match &self.form {
            Some(form) => form.keys(),
            None if self.editing => " ⏎ analyze   Esc done   ←→ Ctrl+A/E/U/K/W edit",
            None => "/ text   b analyzer   r again   Tab panel   ↑↓",
        }
    }

    pub fn render(&mut self, frame: &mut Frame, area: Rect) {
        let analyzer_lines = self.analyzer_lines();
        let analyzer_height = Paragraph::new(analyzer_lines.clone())
            .wrap(Wrap { trim: false })
            .line_count(area.width.saturating_sub(2)) as u16;
        let [analyzer_area, text_area, body] = Layout::vertical([
            Constraint::Length(analyzer_height.clamp(1, 4) + 2),
            Constraint::Length(3),
            Constraint::Fill(1),
        ])
        .areas(area);
        frame.render_widget(
            Paragraph::new(analyzer_lines)
                .wrap(Wrap { trim: false })
                .block(panel(" Analyzer · b builds it ".to_owned(), false)),
            analyzer_area,
        );
        self.render_text(frame, text_area);
        match &self.outcome {
            None => {
                frame.render_widget(
                    Paragraph::new(vec![
                        Line::from("Press / to type a text, then Enter to analyze it.")
                            .style(dim()),
                        Line::from(
                            "Each step shows what the analyzer has made of it so far, and each \
                             token every attribute.",
                        )
                        .style(dim()),
                    ])
                    .wrap(Wrap { trim: false })
                    .block(panel(" Analysis ".to_owned(), false)),
                    body,
                );
            }
            Some(Err(error)) => {
                frame.render_widget(
                    Paragraph::new(error.clone())
                        .style(Style::new().fg(Color::Red))
                        .wrap(Wrap { trim: false })
                        .block(panel(" Analysis failed ".to_owned(), false)),
                    body,
                );
            }
            Some(Ok(_)) => {
                let [steps_area, rest] =
                    Layout::horizontal([Constraint::Length(26), Constraint::Fill(1)]).areas(body);
                self.render_steps(frame, steps_area);
                if area.width >= WIDE {
                    let [tokens_area, details_area] =
                        Layout::horizontal([Constraint::Fill(1), Constraint::Length(48)])
                            .areas(rest);
                    self.render_step(frame, tokens_area);
                    self.render_details(frame, details_area);
                } else {
                    let [tokens_area, details_area] =
                        Layout::vertical([Constraint::Fill(3), Constraint::Fill(2)]).areas(rest);
                    self.render_step(frame, tokens_area);
                    self.render_details(frame, details_area);
                }
            }
        }
        if let Some(form) = &self.form {
            form.render(frame, area);
        }
    }

    /// The current analyzer, and where the component names came from.
    fn analyzer_lines(&self) -> Vec<Line<'static>> {
        let mut lines = vec![Line::from(self.settings.text())];
        if let Some(Err(error)) = &self.components {
            lines.push(
                Line::from(format!("Cannot list the components: {error}"))
                    .style(Style::new().fg(Color::Red)),
            );
        }
        lines
    }

    fn render_text(&self, frame: &mut Frame, area: Rect) {
        let block = panel(" Text ".to_owned(), self.editing);
        let inner = block.inner(area);
        frame.render_widget(block, area);
        if self.editing {
            let (shown, cursor_x) = self.input.visible(inner.width);
            frame.render_widget(Line::from(shown.to_owned()), inner);
            frame.set_cursor_position((inner.x + cursor_x, inner.y));
        } else if self.input.text().is_empty() {
            frame.render_widget(Line::from("Press / to type a text.").style(dim()), inner);
        } else {
            let mut line = vec![Span::raw(escape_control(self.input.text()))];
            if self
                .shown()
                .is_some_and(|shown| shown.text != self.input.text())
            {
                line.push(Span::styled("   (not analyzed yet: Enter)", dim()));
            }
            frame.render_widget(Line::from(line), inner);
        }
    }

    fn render_steps(&mut self, frame: &mut Frame, area: Rect) {
        let focused = self.focus == Focus::Steps && !self.prompting();
        let Some(shown) = self.shown() else {
            return;
        };
        let result = &shown.result;
        let mut items: Vec<ListItem> = result
            .char_filters
            .iter()
            .map(|step| {
                ListItem::new(Line::from(vec![
                    Span::raw(format!("{:<16.16} ", escape_control(&step.name))),
                    Span::styled(format!("{:>6}", "text"), dim()),
                ]))
            })
            .collect();
        items.extend(result.stages.iter().map(|stage| {
            let count = if stage.truncated {
                format!("{}+", stage.tokens.len())
            } else {
                stage.tokens.len().to_string()
            };
            ListItem::new(format!("{:<16.16} {count:>6}", escape_control(&stage.name)))
        }));
        let list = List::new(items)
            .highlight_style(highlight(focused))
            .block(panel(" Steps ".to_owned(), focused));
        frame.render_stateful_widget(list, area, &mut self.steps);
    }

    /// The text after the selected char filter, or the tokens of the selected stage.
    fn render_step(&mut self, frame: &mut Frame, area: Rect) {
        let focused = self.focus == Focus::Tokens && !self.prompting();
        let (Some(step), Some(shown)) = (self.step(), self.shown()) else {
            frame.render_widget(panel(" Tokens ".to_owned(), focused), area);
            return;
        };
        let result = &shown.result;
        let stage = match step {
            Step::CharFilter(index) => {
                let step = &result.char_filters[index];
                frame.render_widget(
                    Paragraph::new(escape_control(&step.text))
                        .wrap(Wrap { trim: false })
                        .block(panel(
                            format!(" Text after {} ", escape_control(&step.name)),
                            focused,
                        )),
                    area,
                );
                return;
            }
            Step::Stage(index) => &result.stages[index],
        };
        let mut title = format!(
            " Tokens after {} · {} ",
            escape_control(&stage.name),
            stage.tokens.len()
        );
        if stage.truncated {
            title.push_str(&format!("· first {MAX_TOKENS} only "));
        }
        let block = panel(title, focused);
        if stage.tokens.is_empty() {
            frame.render_widget(Paragraph::new("No token.").style(dim()).block(block), area);
            return;
        }
        let rows: Vec<Row> = stage
            .tokens
            .iter()
            .map(|token| {
                let position = position_text(token);
                let mut row = Row::new(vec![
                    Cell::from(position),
                    Cell::from(escape_control(&token.term)),
                    Cell::from(format!("{}–{}", token.start_offset, token.end_offset)),
                    Cell::from(escape_control(&token.token_type)),
                ]);
                if token.position_increment == 0 {
                    // Stacked on the token before, such as a synonym.
                    row = row.style(Style::new().add_modifier(Modifier::ITALIC));
                }
                row
            })
            .collect();
        let width = |f: fn(&Token) -> usize| {
            stage
                .tokens
                .iter()
                .map(f)
                .max()
                .unwrap_or_default()
                .clamp(3, 40) as u16
        };
        let widths = [
            Constraint::Length(width(|t| position_text(t).chars().count())),
            Constraint::Fill(1),
            Constraint::Length(
                width(|t| {
                    format!("{}–{}", t.start_offset, t.end_offset)
                        .chars()
                        .count()
                })
                .max(7),
            ),
            Constraint::Length(width(|t| t.token_type.chars().count()).max(4)),
        ];
        let header = Row::new(["Pos", "Term", "Offsets", "Type"])
            .style(Style::new().add_modifier(Modifier::BOLD));
        let table = Table::new(rows, widths)
            .header(header)
            .row_highlight_style(highlight(focused))
            .block(block);
        frame.render_stateful_widget(table, area, &mut self.tokens);
    }

    /// Every attribute of the selected token.
    fn render_details(&self, frame: &mut Frame, area: Rect) {
        let focused = self.focus == Focus::Details && !self.prompting();
        let Some(token) = self.selected_token() else {
            let hint = match self.step() {
                Some(Step::CharFilter(_)) => "A char filter changes the text, before any token.",
                _ => "",
            };
            frame.render_widget(
                Paragraph::new(hint)
                    .style(dim())
                    .wrap(Wrap { trim: false })
                    .block(panel(" Token ".to_owned(), focused)),
                area,
            );
            return;
        };
        let mut lines = vec![
            Line::from(format!("term              {}", escape_control(&token.term))),
            Line::from(format!("position          {}", token.position)),
            Line::from(format!("positionIncrement {}", token.position_increment)),
            Line::from(format!("positionLength    {}", token.position_length)),
            Line::from(format!(
                "offsets           {}–{}",
                token.start_offset, token.end_offset
            )),
            Line::from(format!(
                "type              {}",
                escape_control(&token.token_type)
            )),
        ];
        let mut attribute = None;
        for value in &token.attributes {
            if attribute != Some(&value.attribute) {
                attribute = Some(&value.attribute);
                lines.push(Line::from(""));
                lines.push(
                    Line::from(escape_control(&value.attribute))
                        .style(Style::new().add_modifier(Modifier::BOLD)),
                );
            }
            lines.push(Line::from(format!(
                "  {:<15} {}",
                escape_control(&value.key),
                escape_control(&value.value)
            )));
        }
        let height = area.height.saturating_sub(2);
        let total = Paragraph::new(lines.clone())
            .wrap(Wrap { trim: false })
            .line_count(area.width.saturating_sub(2));
        let max_scroll = u16::try_from(total)
            .unwrap_or(u16::MAX)
            .saturating_sub(height);
        frame.render_widget(
            Paragraph::new(lines)
                .wrap(Wrap { trim: false })
                .scroll((self.details_scroll.min(max_scroll), 0))
                .block(panel(" Token ".to_owned(), focused)),
            area,
        );
    }
}

/// The token's position, and the last one it spans if more than one, such as a multi-word
/// synonym; `·` marks a gap before it, where a token was removed, such as a stop word.
fn position_text(token: &Token) -> String {
    let mut text = token.position.to_string();
    if token.position_length > 1 {
        text.push_str(&format!("–{}", token.position + token.position_length - 1));
    }
    if token.position_increment > 1 {
        text.push('·');
    }
    text
}

/// Whether the server would refuse `text`: it counts UTF-16 code units, as Java does.
fn too_long(text: &str) -> bool {
    text.encode_utf16().count() > MAX_TEXT_LENGTH
}

#[cfg(test)]
mod tests {
    use ratatui::Terminal;
    use ratatui::backend::TestBackend;
    use ratatui::crossterm::event::KeyModifiers;

    use super::*;
    use crate::protocol::analysis::{Analyzer, CharFilterStep, TokenAttribute, TokenStage};

    fn key(code: KeyCode) -> KeyEvent {
        KeyEvent::new(code, KeyModifiers::NONE)
    }

    fn press(analysis: &mut Analysis, code: KeyCode) -> Option<Request> {
        analysis.handle_key(key(code))
    }

    fn typed(analysis: &mut Analysis, text: &str) {
        for c in text.chars() {
            assert_eq!(press(analysis, KeyCode::Char(c)), None);
        }
    }

    fn token(term: &str, position: i32, increment: i32) -> Token {
        Token {
            term: term.to_owned(),
            position,
            position_increment: increment,
            position_length: 1,
            start_offset: 0,
            end_offset: term.len() as i32,
            token_type: "<ALPHANUM>".to_owned(),
            attributes: vec![TokenAttribute {
                attribute: "KeywordAttribute".to_owned(),
                key: "keyword".to_owned(),
                value: "false".to_owned(),
            }],
        }
    }

    fn result() -> AnalyzeResult {
        AnalyzeResult {
            char_filters: vec![CharFilterStep {
                name: "htmlStrip".to_owned(),
                text: "The Fox".to_owned(),
            }],
            stages: vec![
                TokenStage {
                    name: "standard".to_owned(),
                    tokens: vec![token("The", 0, 1), token("Fox", 1, 1)],
                    truncated: false,
                },
                TokenStage {
                    name: "stop".to_owned(),
                    tokens: vec![token("fox", 1, 2)],
                    truncated: false,
                },
            ],
        }
    }

    fn render(analysis: &mut Analysis, width: u16) -> String {
        let mut terminal = Terminal::new(TestBackend::new(width, 30)).unwrap();
        terminal
            .draw(|frame| analysis.render(frame, frame.area()))
            .unwrap();
        format!("{}", terminal.backend())
    }

    #[test]
    fn asks_for_the_components_once() {
        let mut analysis = Analysis::new();
        assert_eq!(analysis.initial_request(), Some(Request::Components));
        analysis.components_failed("down".to_owned());
        assert_eq!(analysis.initial_request(), None);
        assert!(render(&mut analysis, 100).contains("Cannot list the components: down"));
    }

    #[test]
    fn analyzes_the_typed_text_with_the_current_analyzer() {
        let mut analysis = Analysis::new();
        assert_eq!(press(&mut analysis, KeyCode::Char('r')), None);
        press(&mut analysis, KeyCode::Char('/'));
        assert!(analysis.prompting());
        typed(&mut analysis, "<b>The</b> Fox");
        assert_eq!(
            press(&mut analysis, KeyCode::Enter),
            Some(Request::Analyze(AnalyzeParams {
                analyzer: Analyzer::default(),
                text: "<b>The</b> Fox".to_owned(),
            }))
        );
        assert!(!analysis.prompting());
        analysis.analyzed("<b>The</b> Fox".to_owned(), result());

        // The last stage is selected, with its first token.
        assert_eq!(analysis.step(), Some(Step::Stage(1)));
        assert_eq!(analysis.selected_token().unwrap().term, "fox");
        let screen = render(&mut analysis, 130);
        for text in [
            "standard analyzer",
            "htmlStrip",
            "Tokens after stop · 1",
            "1·",
            "KeywordAttribute",
        ] {
            assert!(screen.contains(text), "{text:?} missing:\n{screen}");
        }

        // r analyzes again; the steps go up to the char filter's text.
        assert!(matches!(
            press(&mut analysis, KeyCode::Char('r')),
            Some(Request::Analyze(_))
        ));
        press(&mut analysis, KeyCode::BackTab);
        press(&mut analysis, KeyCode::Up);
        assert_eq!(analysis.step(), Some(Step::Stage(0)));
        assert_eq!(analysis.selected_token().unwrap().term, "The");
        press(&mut analysis, KeyCode::Up);
        assert_eq!(analysis.step(), Some(Step::CharFilter(0)));
        assert_eq!(analysis.selected_token(), None);
        assert!(render(&mut analysis, 100).contains("Text after htmlStrip"));
    }

    #[test]
    fn refuses_a_text_the_server_would_refuse() {
        let mut analysis = Analysis::new();
        press(&mut analysis, KeyCode::Char('/'));
        analysis.input.set("é".repeat(MAX_TEXT_LENGTH + 1));
        assert_eq!(press(&mut analysis, KeyCode::Enter), None);
        assert!(analysis.prompting());
        assert!(matches!(&analysis.outcome, Some(Err(error)) if error.contains("longer")));
    }

    #[test]
    fn an_applied_analyzer_becomes_current_once_the_server_took_it() {
        let mut analysis = Analysis::new();
        press(&mut analysis, KeyCode::Char('b'));
        // Switch to the custom chain and apply.
        press(&mut analysis, KeyCode::Char(' '));
        let Some(Request::Analyze(params)) = press(&mut analysis, KeyCode::Enter) else {
            panic!("expected an analysis");
        };
        assert!(params.analyzer.custom.is_some());
        assert!(!analysis.settings().is_custom());

        // Refused: the form stays open with the error, the analyzer stays as it was.
        analysis.analyze_failed("Invalid analyzer: no tokenizer 'x'".to_owned());
        assert!(analysis.prompting());
        assert!(!analysis.settings().is_custom());
        assert!(render(&mut analysis, 100).contains("no tokenizer 'x'"));

        // Taken: the form closes and the analyzer is the current one.
        press(&mut analysis, KeyCode::Enter);
        analysis.analyzed(String::new(), AnalyzeResult::default());
        assert!(!analysis.prompting());
        assert!(analysis.settings().is_custom());
        assert!(render(&mut analysis, 100).contains("standard › lowercase"));

        // Esc closes the form without a change.
        press(&mut analysis, KeyCode::Char('b'));
        press(&mut analysis, KeyCode::Char(' '));
        assert_eq!(press(&mut analysis, KeyCode::Esc), None);
        assert!(!analysis.prompting());
        assert!(analysis.settings().is_custom());
    }

    #[test]
    fn positions_show_gaps_and_spans() {
        assert_eq!(position_text(&token("fox", 3, 1)), "3");
        assert_eq!(position_text(&token("fox", 3, 2)), "3·");
        let span = Token {
            position_length: 2,
            ..token("wireless", 1, 1)
        };
        assert_eq!(position_text(&span), "1–2");
    }

    #[test]
    fn shows_why_the_analysis_failed() {
        let mut analysis = Analysis::new();
        analysis
            .analyze_failed("Invalid analyzer: config directory: /x is not a directory".to_owned());
        assert!(render(&mut analysis, 100).contains("/x is not a directory"));
    }
}
