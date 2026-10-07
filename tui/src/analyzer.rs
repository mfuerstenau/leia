//! The current analyzer (ADR 0014): the analysis screen tries it, and the search screen parses
//! queries and analyzes More-Like-This text with it. It is Lucene's `StandardAnalyzer`, or a custom
//! analyzer of char filters, a tokenizer and token filters, each with its parameters, and a config
//! directory for resource files. The popup builds it.

use std::collections::BTreeMap;

use ratatui::Frame;
use ratatui::crossterm::event::{KeyCode, KeyEvent};
use ratatui::layout::Rect;
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Clear, Paragraph, Wrap};

use crate::form::{FormEvent, dim, render_popup};
use crate::input::{Edit, LineInput, word_start};
use crate::protocol::analysis::{
    AnalysisComponent, Analyzer, AvailableComponent, CustomAnalyzer, GetAnalysisComponentsResult,
};
use crate::text::escape_control;

/// How wide the popups are at most.
const WIDTH: u16 = 76;

/// The current analyzer. A custom chain is kept while the standard analyzer is chosen, so that
/// switching back finds it again.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AnalyzerSettings {
    custom: bool,
    chain: CustomAnalyzer,
}

impl Default for AnalyzerSettings {
    /// The standard analyzer, and a custom chain that does what it does: the standard tokenizer,
    /// then lowercase.
    fn default() -> Self {
        Self {
            custom: false,
            chain: CustomAnalyzer {
                char_filters: Vec::new(),
                tokenizer: component("standard"),
                token_filters: vec![component("lowercase")],
                config_dir: None,
            },
        }
    }
}

impl AnalyzerSettings {
    /// The analyzer as a request sends it.
    pub fn analyzer(&self) -> Analyzer {
        if self.custom {
            Analyzer {
                standard: None,
                custom: Some(self.chain.clone()),
            }
        } else {
            Analyzer::default()
        }
    }

    pub fn is_custom(&self) -> bool {
        self.custom
    }

    /// The analyzer on one line: `standard analyzer`, or the chain, such as `htmlStrip › standard
    /// › lowercase › stop(ignoreCase=true)`, and its config directory.
    pub fn text(&self) -> String {
        if !self.custom {
            return "standard analyzer".to_owned();
        }
        let chain = &self.chain;
        let mut text = chain
            .char_filters
            .iter()
            .chain([&chain.tokenizer])
            .chain(&chain.token_filters)
            .map(component_text)
            .collect::<Vec<_>>()
            .join(" › ");
        if let Some(dir) = &chain.config_dir {
            text.push_str(&format!(" · config {}", escape_control(dir)));
        }
        text
    }
}

fn component(name: &str) -> AnalysisComponent {
    AnalysisComponent {
        name: name.to_owned(),
        params: BTreeMap::new(),
    }
}

/// `name`, or `name(key=value, …)` with its parameters.
fn component_text(component: &AnalysisComponent) -> String {
    let name = escape_control(&component.name);
    if component.params.is_empty() {
        return name;
    }
    let params: Vec<String> = component
        .params
        .iter()
        .map(|(key, value)| format!("{}={}", escape_control(key), escape_control(value)))
        .collect();
    format!("{name}({})", params.join(", "))
}

/// A part of the custom chain.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Section {
    CharFilters,
    Tokenizer,
    TokenFilters,
}

impl Section {
    fn heading(self) -> &'static str {
        match self {
            Section::CharFilters => "Char filters",
            Section::Tokenizer => "Tokenizer",
            Section::TokenFilters => "Token filters",
        }
    }

    /// What one component of the section is called.
    fn noun(self) -> &'static str {
        match self {
            Section::CharFilters => "char filter",
            Section::Tokenizer => "tokenizer",
            Section::TokenFilters => "token filter",
        }
    }

    fn available(self, components: &GetAnalysisComponentsResult) -> &[AvailableComponent] {
        match self {
            Section::CharFilters => &components.char_filters,
            Section::Tokenizer => &components.tokenizers,
            Section::TokenFilters => &components.token_filters,
        }
    }
}

/// One selectable line of the popup.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Row {
    /// Standard or custom.
    Kind,
    ConfigDir,
    /// A component: its section and its index there (0 for the tokenizer).
    Component(Section, usize),
    /// A parameter of a component, by its index in key order.
    Param(Section, usize, usize),
    /// Adds a char filter or token filter at the end.
    Add(Section),
}

/// Where a picked component goes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Target {
    Replace(usize),
    Insert(usize),
}

/// The list of components to pick from, filtered as you type.
#[derive(Debug)]
struct Picker {
    section: Section,
    target: Target,
    filter: LineInput,
    selected: usize,
}

/// What a line input edits.
#[derive(Debug, Clone, PartialEq, Eq)]
enum TextTarget {
    ConfigDir,
    /// A parameter of a component as `name=value`; `key` is the one it replaces, if any.
    Param {
        section: Section,
        index: usize,
        key: Option<String>,
    },
}

#[derive(Debug)]
struct TextEdit {
    target: TextTarget,
    input: LineInput,
    error: Option<String>,
}

#[derive(Debug)]
enum Editing {
    None,
    Pick(Picker),
    Text(TextEdit),
}

/// The popup: a draft of the analyzer, which the caller tries before it takes it.
#[derive(Debug)]
pub struct AnalyzerForm {
    draft: AnalyzerSettings,
    /// What a custom analyzer can be built from; `None` while it is not loaded.
    components: Option<GetAnalysisComponentsResult>,
    selected: usize,
    editing: Editing,
    /// Why the draft was refused, until the next change.
    error: Option<String>,
}

impl AnalyzerForm {
    pub fn new(
        settings: &AnalyzerSettings,
        components: Option<GetAnalysisComponentsResult>,
    ) -> Self {
        Self {
            draft: settings.clone(),
            components,
            selected: 0,
            editing: Editing::None,
            error: None,
        }
    }

    /// Shows why the server refused the draft; the popup stays open to fix it.
    pub fn set_error(&mut self, error: String) {
        self.error = Some(error);
    }

    pub fn set_components(&mut self, components: GetAnalysisComponentsResult) {
        self.components = Some(components);
    }

    fn rows(&self) -> Vec<Row> {
        let mut rows = vec![Row::Kind];
        if !self.draft.custom {
            return rows;
        }
        rows.push(Row::ConfigDir);
        let chain = &self.draft.chain;
        for (section, components) in [
            (Section::CharFilters, &chain.char_filters),
            (Section::Tokenizer, &vec![chain.tokenizer.clone()]),
            (Section::TokenFilters, &chain.token_filters),
        ] {
            for (index, component) in components.iter().enumerate() {
                rows.push(Row::Component(section, index));
                rows.extend(
                    (0..component.params.len()).map(|param| Row::Param(section, index, param)),
                );
            }
            if section != Section::Tokenizer {
                rows.push(Row::Add(section));
            }
        }
        rows
    }

    fn select(&mut self, row: Row) {
        if let Some(index) = self.rows().iter().position(|r| *r == row) {
            self.selected = index;
        }
    }

    fn list_mut(&mut self, section: Section) -> Option<&mut Vec<AnalysisComponent>> {
        match section {
            Section::CharFilters => Some(&mut self.draft.chain.char_filters),
            Section::Tokenizer => None,
            Section::TokenFilters => Some(&mut self.draft.chain.token_filters),
        }
    }

    fn component(&self, section: Section, index: usize) -> Option<&AnalysisComponent> {
        let chain = &self.draft.chain;
        match section {
            Section::CharFilters => chain.char_filters.get(index),
            Section::Tokenizer => Some(&chain.tokenizer),
            Section::TokenFilters => chain.token_filters.get(index),
        }
    }

    fn component_mut(&mut self, section: Section, index: usize) -> Option<&mut AnalysisComponent> {
        let chain = &mut self.draft.chain;
        match section {
            Section::CharFilters => chain.char_filters.get_mut(index),
            Section::Tokenizer => Some(&mut chain.tokenizer),
            Section::TokenFilters => chain.token_filters.get_mut(index),
        }
    }

    /// While a picker or a line input is open, it takes the keys. Otherwise ↑↓ select a row;
    /// Space or → changes it (standard or custom, the config directory, another component, a
    /// parameter); `a` adds a filter after the selected one, `p` a parameter to the selected
    /// component, `d` deletes, `K`/`J` move a filter up or down; Enter applies, Esc cancels.
    pub fn handle_key(&mut self, key: KeyEvent) -> FormEvent<AnalyzerSettings> {
        match &mut self.editing {
            Editing::Pick(_) => {
                self.pick_key(key);
                return FormEvent::None;
            }
            Editing::Text(_) => {
                self.text_key(key);
                return FormEvent::None;
            }
            Editing::None => {}
        }
        let rows = self.rows();
        let last = rows.len() - 1;
        let row = rows[self.selected.min(last)];
        match key.code {
            KeyCode::Enter => return FormEvent::Apply(self.draft.clone()),
            KeyCode::Esc => return FormEvent::Cancel,
            KeyCode::Up | KeyCode::Char('k') => self.selected = self.selected.saturating_sub(1),
            KeyCode::Down | KeyCode::Char('j') => self.selected = (self.selected + 1).min(last),
            KeyCode::PageUp => self.selected = self.selected.saturating_sub(10),
            KeyCode::PageDown => self.selected = (self.selected + 10).min(last),
            KeyCode::Home | KeyCode::Char('g') => self.selected = 0,
            KeyCode::End | KeyCode::Char('G') => self.selected = last,
            KeyCode::Char(' ') | KeyCode::Right | KeyCode::Left | KeyCode::Char('l') => {
                self.change(row);
            }
            KeyCode::Char('a') => self.add_after(row),
            KeyCode::Char('p') => {
                if let Row::Component(section, index) | Row::Param(section, index, _) = row {
                    self.edit_text(TextTarget::Param {
                        section,
                        index,
                        key: None,
                    });
                }
            }
            KeyCode::Char('d') | KeyCode::Delete | KeyCode::Backspace => self.delete(row),
            KeyCode::Char('K') => self.move_component(row, false),
            KeyCode::Char('J') => self.move_component(row, true),
            _ => {}
        }
        FormEvent::None
    }

    fn change(&mut self, row: Row) {
        match row {
            Row::Kind => {
                self.draft.custom = !self.draft.custom;
                self.error = None;
            }
            Row::ConfigDir => self.edit_text(TextTarget::ConfigDir),
            Row::Component(section, index) => self.pick(section, Target::Replace(index)),
            Row::Param(section, index, param) => {
                let key = self
                    .component(section, index)
                    .and_then(|component| component.params.keys().nth(param))
                    .cloned();
                self.edit_text(TextTarget::Param {
                    section,
                    index,
                    key,
                });
            }
            Row::Add(section) => {
                let end = self.list_mut(section).map_or(0, |list| list.len());
                self.pick(section, Target::Insert(end));
            }
        }
    }

    /// Adds a filter after the selected one, or at the end of the selected section.
    fn add_after(&mut self, row: Row) {
        match row {
            Row::Component(section, index) | Row::Param(section, index, _)
                if section != Section::Tokenizer =>
            {
                self.pick(section, Target::Insert(index + 1));
            }
            Row::Add(_) => self.change(row),
            _ => {}
        }
    }

    fn pick(&mut self, section: Section, target: Target) {
        self.editing = Editing::Pick(Picker {
            section,
            target,
            filter: LineInput::new(""),
            selected: 0,
        });
    }

    fn edit_text(&mut self, target: TextTarget) {
        let text = match &target {
            TextTarget::ConfigDir => self.draft.chain.config_dir.clone().unwrap_or_default(),
            TextTarget::Param {
                section,
                index,
                key: Some(key),
            } => {
                let value = self
                    .component(*section, *index)
                    .and_then(|component| component.params.get(key))
                    .cloned()
                    .unwrap_or_default();
                format!("{key}={value}")
            }
            TextTarget::Param { key: None, .. } => String::new(),
        };
        self.editing = Editing::Text(TextEdit {
            target,
            input: LineInput::new(&text),
            error: None,
        });
    }

    fn delete(&mut self, row: Row) {
        match row {
            Row::ConfigDir => self.draft.chain.config_dir = None,
            Row::Component(section, index) => {
                let Some(list) = self.list_mut(section) else {
                    return;
                };
                list.remove(index);
                // The selection stays on the row that moved up, or the add row.
                self.selected = self.selected.min(self.rows().len() - 1);
            }
            Row::Param(section, index, param) => {
                if let Some(component) = self.component_mut(section, index)
                    && let Some(key) = component.params.keys().nth(param).cloned()
                {
                    component.params.remove(&key);
                }
                self.selected = self.selected.min(self.rows().len() - 1);
            }
            Row::Kind | Row::Add(_) => return,
        }
        self.error = None;
    }

    fn move_component(&mut self, row: Row, down: bool) {
        let (Row::Component(section, index) | Row::Param(section, index, _)) = row else {
            return;
        };
        let Some(list) = self.list_mut(section) else {
            return;
        };
        let to = if down {
            index + 1
        } else {
            let Some(to) = index.checked_sub(1) else {
                return;
            };
            to
        };
        if to >= list.len() {
            return;
        }
        list.swap(index, to);
        self.select(Row::Component(section, to));
        self.error = None;
    }

    /// The components of the picker's section that contain the filter, ignoring case.
    fn matches(&self, picker: &Picker) -> Vec<AvailableComponent> {
        let filter = picker.filter.text().to_lowercase();
        self.components
            .as_ref()
            .map(|components| picker.section.available(components))
            .unwrap_or_default()
            .iter()
            .filter(|component| component.name.to_lowercase().contains(&filter))
            .cloned()
            .collect()
    }

    /// ↑↓ select, typing filters, Enter takes the selected component (or the typed name if none
    /// matches), Esc goes back.
    fn pick_key(&mut self, key: KeyEvent) {
        let Editing::Pick(picker) = &self.editing else {
            return;
        };
        let matches = self.matches(picker);
        let Editing::Pick(picker) = &mut self.editing else {
            return;
        };
        let last = matches.len().saturating_sub(1);
        match key.code {
            KeyCode::Esc => self.editing = Editing::None,
            KeyCode::Up => picker.selected = picker.selected.saturating_sub(1),
            KeyCode::Down => picker.selected = (picker.selected + 1).min(last),
            KeyCode::PageUp => picker.selected = picker.selected.saturating_sub(10),
            KeyCode::PageDown => picker.selected = (picker.selected + 10).min(last),
            KeyCode::Enter => {
                let name = match matches.get(picker.selected.min(last)) {
                    Some(component) => component.name.clone(),
                    None => picker.filter.text().trim().to_owned(),
                };
                if name.is_empty() {
                    return;
                }
                let (section, target) = (picker.section, picker.target);
                self.editing = Editing::None;
                self.put(section, target, name);
            }
            _ => {
                if picker.filter.handle_key(key, word_start) == Edit::Changed {
                    picker.selected = 0;
                }
            }
        }
    }

    /// Puts the component `name`, without parameters, where the picker was opened for.
    fn put(&mut self, section: Section, target: Target, name: String) {
        let new = AnalysisComponent {
            name,
            params: BTreeMap::new(),
        };
        let index = match (target, section) {
            (Target::Replace(_), Section::Tokenizer) => {
                self.draft.chain.tokenizer = new;
                0
            }
            (Target::Replace(index), _) => {
                if let Some(list) = self.list_mut(section)
                    && let Some(component) = list.get_mut(index)
                {
                    *component = new;
                }
                index
            }
            (Target::Insert(index), _) => {
                let Some(list) = self.list_mut(section) else {
                    return;
                };
                let index = index.min(list.len());
                list.insert(index, new);
                index
            }
        };
        self.select(Row::Component(section, index));
        self.error = None;
    }

    /// Enter takes the text, Esc goes back; any other key edits it.
    fn text_key(&mut self, key: KeyEvent) {
        let Editing::Text(edit) = &mut self.editing else {
            return;
        };
        match key.code {
            KeyCode::Esc => self.editing = Editing::None,
            KeyCode::Enter => {
                let text = edit.input.text().to_owned();
                let target = edit.target.clone();
                match self.take_text(target, &text) {
                    Ok(()) => {
                        self.editing = Editing::None;
                        self.error = None;
                    }
                    Err(error) => {
                        if let Editing::Text(edit) = &mut self.editing {
                            edit.error = Some(error);
                        }
                    }
                }
            }
            _ => {
                edit.input.handle_key(key, word_start);
            }
        }
    }

    /// Applies an edited text: a config directory (none if blank), or a parameter as
    /// `name=value` (removed if blank).
    fn take_text(&mut self, target: TextTarget, text: &str) -> Result<(), String> {
        let text = text.trim();
        let (section, index, old) = match target {
            TextTarget::ConfigDir => {
                self.draft.chain.config_dir = (!text.is_empty()).then(|| text.to_owned());
                return Ok(());
            }
            TextTarget::Param {
                section,
                index,
                key,
            } => (section, index, key),
        };
        let parsed = if text.is_empty() {
            None
        } else {
            match text.split_once('=') {
                Some((key, value)) if !key.trim().is_empty() => {
                    Some((key.trim().to_owned(), value.trim().to_owned()))
                }
                _ => return Err("Write the parameter as name=value.".to_owned()),
            }
        };
        let Some(component) = self.component_mut(section, index) else {
            return Ok(());
        };
        if let Some(old) = &old {
            component.params.remove(old);
        }
        let Some((key, value)) = parsed else {
            self.selected = self.selected.min(self.rows().len() - 1);
            return Ok(());
        };
        component.params.insert(key.clone(), value);
        let param = component
            .params
            .keys()
            .position(|k| *k == key)
            .unwrap_or_default();
        self.select(Row::Param(section, index, param));
        Ok(())
    }

    /// The keys for the footer.
    pub fn keys(&self) -> &'static str {
        match &self.editing {
            Editing::Pick(_) => " type to filter   ↑↓ select   ⏎ take   Esc back",
            Editing::Text(_) => " ⏎ take   Esc back   ←→ Ctrl+A/E/U/K/W edit",
            Editing::None if self.draft.custom => {
                " ␣ change   a add filter   p add parameter   d delete   K/J move   ⏎ apply   \
                 Esc cancel   ↑↓"
            }
            Editing::None => " ␣ standard/custom   ⏎ apply   Esc cancel",
        }
    }

    pub fn render(&self, frame: &mut Frame, area: Rect) {
        let rows = self.rows();
        let selected = self.selected.min(rows.len() - 1);
        let mut lines = Vec::new();
        let mut selected_line = 0;
        let mut section = None;
        for (i, row) in rows.iter().enumerate() {
            if let Row::Component(s, _) | Row::Add(s) = row
                && section != Some(*s)
            {
                section = Some(*s);
                lines.push(Line::from(""));
                lines
                    .push(Line::from(s.heading()).style(Style::new().add_modifier(Modifier::BOLD)));
            }
            if i == selected {
                selected_line = lines.len();
            }
            let line = self.row_line(*row);
            lines.push(if i == selected {
                line.style(Style::new().add_modifier(Modifier::REVERSED))
            } else {
                line
            });
        }
        if !self.draft.custom {
            lines.push(Line::from(""));
            lines.push(
                Line::from("Lucene's StandardAnalyzer: the standard tokenizer, then lowercase.")
                    .style(dim()),
            );
            lines.push(
                Line::from("Space switches to a custom analyzer of your own chain.").style(dim()),
            );
        }
        if let Some(error) = &self.error {
            lines.push(Line::from(""));
            let width = usize::from(area.width.min(WIDTH).saturating_sub(2));
            for line in wrap_words(error, width) {
                lines.push(Line::from(line).style(Style::new().fg(Color::Red)));
            }
            selected_line = lines.len() - 1;
        }
        render_popup(frame, area, " Analyzer ", lines, selected_line, WIDTH);
        match &self.editing {
            Editing::None => {}
            Editing::Pick(picker) => self.render_picker(frame, area, picker),
            Editing::Text(edit) => render_text(frame, area, edit),
        }
    }

    fn row_line(&self, row: Row) -> Line<'static> {
        match row {
            Row::Kind => Line::from(format!(
                "Analyzer       {}",
                if self.draft.custom {
                    "custom"
                } else {
                    "standard"
                }
            )),
            Row::ConfigDir => match &self.draft.chain.config_dir {
                Some(dir) => Line::from(format!("Config dir     {}", escape_control(dir))),
                None => Line::from(vec![
                    Span::raw("Config dir     "),
                    Span::styled("none: resources from the classpath", dim()),
                ]),
            },
            Row::Component(section, index) => {
                let name = self
                    .component(section, index)
                    .map(|component| escape_control(&component.name))
                    .unwrap_or_default();
                Line::from(format!("  {name}"))
            }
            Row::Param(section, index, param) => {
                let (key, value) = self
                    .component(section, index)
                    .and_then(|component| component.params.iter().nth(param))
                    .map(|(key, value)| (escape_control(key), escape_control(value)))
                    .unwrap_or_default();
                Line::from(format!("      {key} = {value}"))
            }
            Row::Add(section) => Line::from(format!("  + {}", section.noun())).style(dim()),
        }
    }

    fn render_picker(&self, frame: &mut Frame, area: Rect, picker: &Picker) {
        let matches = self.matches(picker);
        let selected = picker.selected.min(matches.len().saturating_sub(1));
        let mut lines = vec![
            Line::from(format!("Filter: {}", picker.filter.text())),
            Line::from(""),
        ];
        if self.components.is_none() {
            lines.push(
                Line::from("The components are not loaded; Enter takes the typed name.")
                    .style(dim()),
            );
        } else if matches.is_empty() {
            lines.push(Line::from("No match; Enter takes the typed name.").style(dim()));
        }
        for (i, component) in matches.iter().enumerate() {
            let class = component
                .class_name
                .rsplit('.')
                .next()
                .unwrap_or_default()
                .to_owned();
            let line = Line::from(vec![
                Span::raw(format!("{:<28} ", escape_control(&component.name))),
                Span::styled(class, dim()),
            ]);
            lines.push(if i == selected {
                line.style(Style::new().add_modifier(Modifier::REVERSED))
            } else {
                line
            });
        }
        let title = format!(" Pick a {} ", picker.section.noun());
        render_popup(frame, area, &title, lines, selected + 2, WIDTH - 8);
    }
}

/// `text` in lines of at most `width` characters, broken between words where it can be.
fn wrap_words(text: &str, width: usize) -> Vec<String> {
    let width = width.max(1);
    let mut lines = Vec::new();
    let mut line = String::new();
    for word in text.split(' ') {
        let mut word = word.to_owned();
        if !line.is_empty() && line.chars().count() + 1 + word.chars().count() > width {
            lines.push(std::mem::take(&mut line));
        }
        if !line.is_empty() {
            line.push(' ');
        }
        // A word longer than a line is broken anywhere.
        while line.chars().count() + word.chars().count() > width {
            let take = width - line.chars().count();
            let split = word.char_indices().nth(take).map_or(word.len(), |(i, _)| i);
            line.push_str(&word[..split]);
            word = word[split..].to_owned();
            lines.push(std::mem::take(&mut line));
        }
        line.push_str(&word);
    }
    lines.push(line);
    lines
}

/// A one-line input in a popup of its own, with the cursor.
fn render_text(frame: &mut Frame, area: Rect, edit: &TextEdit) {
    let title = match edit.target {
        TextTarget::ConfigDir => " Config directory (blank for none) ",
        TextTarget::Param { .. } => " Parameter: name=value (blank removes it) ",
    };
    let width = area.width.min(WIDTH - 4);
    let height = if edit.error.is_some() { 4 } else { 3 }.min(area.height);
    let popup = Rect {
        x: area.x + (area.width - width) / 2,
        y: area.y + (area.height - height) / 2,
        width,
        height,
    };
    let block = Block::bordered().title(title);
    let inner = block.inner(popup);
    frame.render_widget(Clear, popup);
    frame.render_widget(block, popup);
    let (shown, cursor_x) = edit.input.visible(inner.width);
    frame.render_widget(Line::from(shown.to_owned()), inner);
    if let Some(error) = &edit.error
        && inner.height > 1
    {
        let error_area = Rect {
            y: inner.y + 1,
            height: 1,
            ..inner
        };
        frame.render_widget(
            Paragraph::new(error.clone())
                .style(Style::new().fg(Color::Red))
                .wrap(Wrap { trim: false }),
            error_area,
        );
    }
    frame.set_cursor_position((inner.x + cursor_x, inner.y));
}

#[cfg(test)]
mod tests {
    use ratatui::crossterm::event::KeyModifiers;

    use super::*;

    fn key(code: KeyCode) -> KeyEvent {
        KeyEvent::new(code, KeyModifiers::NONE)
    }

    fn press(form: &mut AnalyzerForm, code: KeyCode) -> FormEvent<AnalyzerSettings> {
        form.handle_key(key(code))
    }

    fn typed(form: &mut AnalyzerForm, text: &str) {
        for c in text.chars() {
            assert_eq!(press(form, KeyCode::Char(c)), FormEvent::None);
        }
    }

    fn available(names: &[&str]) -> Vec<AvailableComponent> {
        names
            .iter()
            .map(|name| AvailableComponent {
                name: (*name).to_owned(),
                class_name: format!("org.apache.lucene.analysis.{name}Factory"),
            })
            .collect()
    }

    fn components() -> GetAnalysisComponentsResult {
        GetAnalysisComponentsResult {
            char_filters: available(&["htmlStrip", "mapping"]),
            tokenizers: available(&["keyword", "standard", "whitespace"]),
            token_filters: available(&["asciiFolding", "lowercase", "stop", "stemmerOverride"]),
        }
    }

    fn custom_form() -> AnalyzerForm {
        let mut form = AnalyzerForm::new(&AnalyzerSettings::default(), Some(components()));
        press(&mut form, KeyCode::Char(' '));
        form
    }

    fn applied(form: &mut AnalyzerForm) -> AnalyzerSettings {
        match press(form, KeyCode::Enter) {
            FormEvent::Apply(settings) => settings,
            other => panic!("expected Apply, got {other:?}"),
        }
    }

    #[test]
    fn the_standard_analyzer_is_the_default() {
        let settings = AnalyzerSettings::default();
        assert_eq!(settings.analyzer(), Analyzer::default());
        assert_eq!(settings.text(), "standard analyzer");
        assert!(!settings.is_custom());
    }

    #[test]
    fn space_switches_to_the_custom_chain_of_the_standard_analyzer() {
        let mut form = custom_form();
        let settings = applied(&mut form);
        assert!(settings.is_custom());
        assert_eq!(settings.text(), "standard › lowercase");
        let custom = settings.analyzer().custom.unwrap();
        assert_eq!(custom.tokenizer.name, "standard");
        assert_eq!(custom.token_filters[0].name, "lowercase");
        assert_eq!(settings.analyzer().standard, None);
    }

    #[test]
    fn adds_filters_from_the_filtered_list() {
        let mut form = custom_form();
        // Kind, config dir, + char filter, standard, lowercase, + token filter.
        press(&mut form, KeyCode::End);
        press(&mut form, KeyCode::Char(' '));
        typed(&mut form, "st");
        // "stop" and "stemmerOverride" match; ↓ selects the second.
        press(&mut form, KeyCode::Down);
        press(&mut form, KeyCode::Enter);
        assert_eq!(
            applied(&mut form).text(),
            "standard › lowercase › stemmerOverride"
        );

        // A char filter goes into its own section.
        let mut form = custom_form();
        form.select(Row::Add(Section::CharFilters));
        press(&mut form, KeyCode::Char(' '));
        typed(&mut form, "html");
        press(&mut form, KeyCode::Enter);
        assert_eq!(
            applied(&mut form).text(),
            "htmlStrip › standard › lowercase"
        );

        // `a` on the tokenizer does nothing; on a filter, it inserts after it.
        form.select(Row::Component(Section::Tokenizer, 0));
        press(&mut form, KeyCode::Char('a'));
        assert!(matches!(form.editing, Editing::None));
        form.select(Row::Add(Section::TokenFilters));
        press(&mut form, KeyCode::Char('a'));
        typed(&mut form, "stop");
        press(&mut form, KeyCode::Enter);
        form.select(Row::Component(Section::TokenFilters, 0));
        press(&mut form, KeyCode::Char('a'));
        typed(&mut form, "ascii");
        press(&mut form, KeyCode::Enter);
        assert_eq!(
            applied(&mut form).text(),
            "htmlStrip › standard › lowercase › asciiFolding › stop"
        );
    }

    #[test]
    fn takes_a_typed_name_that_is_not_listed() {
        let mut form = AnalyzerForm::new(&AnalyzerSettings::default(), None);
        press(&mut form, KeyCode::Char(' '));
        press(&mut form, KeyCode::End);
        press(&mut form, KeyCode::Up);
        // The tokenizer row: Space picks another.
        press(&mut form, KeyCode::Up);
        press(&mut form, KeyCode::Char(' '));
        typed(&mut form, "icu");
        press(&mut form, KeyCode::Enter);
        assert_eq!(applied(&mut form).text(), "icu › lowercase");
    }

    #[test]
    fn edits_parameters_as_name_value() {
        let mut form = custom_form();
        // The lowercase filter.
        press(&mut form, KeyCode::End);
        press(&mut form, KeyCode::Up);
        press(&mut form, KeyCode::Char('p'));
        typed(&mut form, "nope");
        press(&mut form, KeyCode::Enter);
        assert!(matches!(&form.editing, Editing::Text(edit) if edit.error.is_some()));
        press(&mut form, KeyCode::Backspace);
        press(&mut form, KeyCode::Backspace);
        press(&mut form, KeyCode::Backspace);
        press(&mut form, KeyCode::Backspace);
        typed(&mut form, " b = 2 ");
        press(&mut form, KeyCode::Enter);
        press(&mut form, KeyCode::Char('p'));
        typed(&mut form, "a=1");
        press(&mut form, KeyCode::Enter);
        assert_eq!(applied(&mut form).text(), "standard › lowercase(a=1, b=2)");

        // The selection is on a=1; Space edits it, and a blank text removes it.
        press(&mut form, KeyCode::Char(' '));
        press(&mut form, KeyCode::Char('u'));
        form.handle_key(KeyEvent::new(KeyCode::Char('u'), KeyModifiers::CONTROL));
        press(&mut form, KeyCode::Enter);
        assert_eq!(applied(&mut form).text(), "standard › lowercase(b=2)");

        // d deletes the selected parameter.
        press(&mut form, KeyCode::End);
        press(&mut form, KeyCode::Up);
        press(&mut form, KeyCode::Char('d'));
        assert_eq!(applied(&mut form).text(), "standard › lowercase");
    }

    #[test]
    fn deletes_and_moves_filters_but_keeps_the_tokenizer() {
        let mut form = custom_form();
        press(&mut form, KeyCode::End);
        press(&mut form, KeyCode::Char(' '));
        typed(&mut form, "stop");
        press(&mut form, KeyCode::Enter);
        // stop is selected; K moves it before lowercase.
        press(&mut form, KeyCode::Char('K'));
        assert_eq!(applied(&mut form).text(), "standard › stop › lowercase");
        press(&mut form, KeyCode::Char('J'));
        assert_eq!(applied(&mut form).text(), "standard › lowercase › stop");
        press(&mut form, KeyCode::Char('d'));
        assert_eq!(applied(&mut form).text(), "standard › lowercase");

        // The tokenizer can't be deleted.
        form.select(Row::Component(Section::Tokenizer, 0));
        press(&mut form, KeyCode::Char('d'));
        assert_eq!(applied(&mut form).text(), "standard › lowercase");
    }

    #[test]
    fn sets_and_clears_the_config_directory() {
        let mut form = custom_form();
        press(&mut form, KeyCode::Down);
        press(&mut form, KeyCode::Char(' '));
        typed(&mut form, " /etc/solr/conf ");
        press(&mut form, KeyCode::Enter);
        let settings = applied(&mut form);
        assert_eq!(
            settings.text(),
            "standard › lowercase · config /etc/solr/conf"
        );
        assert_eq!(
            settings.analyzer().custom.unwrap().config_dir.as_deref(),
            Some("/etc/solr/conf")
        );
        press(&mut form, KeyCode::Char('d'));
        assert_eq!(
            applied(&mut form).analyzer().custom.unwrap().config_dir,
            None
        );
    }

    #[test]
    fn keeps_the_chain_while_the_standard_analyzer_is_chosen() {
        let mut form = custom_form();
        press(&mut form, KeyCode::End);
        press(&mut form, KeyCode::Char(' '));
        typed(&mut form, "stop");
        press(&mut form, KeyCode::Enter);
        press(&mut form, KeyCode::Home);
        press(&mut form, KeyCode::Char(' '));
        let standard = applied(&mut form);
        assert_eq!(standard.analyzer(), Analyzer::default());
        press(&mut form, KeyCode::Char(' '));
        assert_eq!(applied(&mut form).text(), "standard › lowercase › stop");
        assert_eq!(press(&mut form, KeyCode::Esc), FormEvent::Cancel);
    }

    #[test]
    fn wraps_between_words() {
        assert_eq!(wrap_words("ab cd ef", 5), ["ab cd", "ef"]);
        assert_eq!(wrap_words("abcdefg h", 3), ["abc", "def", "g h"]);
        assert_eq!(wrap_words("", 3), [""]);
    }

    #[test]
    fn renders_the_chain_and_the_error() {
        let mut form = custom_form();
        form.set_error("Invalid analyzer: no token filter 'nope'".to_owned());
        let mut terminal =
            ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();
        terminal
            .draw(|frame| form.render(frame, frame.area()))
            .unwrap();
        let screen = format!("{}", terminal.backend());
        for text in [
            "Analyzer       custom",
            "Config dir     none",
            "Char filters",
            "+ char filter",
            "Tokenizer",
            "standard",
            "Token filters",
            "lowercase",
            "Invalid analyzer: no token filter 'nope'",
        ] {
            assert!(screen.contains(text), "{text:?} missing:\n{screen}");
        }
    }
}
