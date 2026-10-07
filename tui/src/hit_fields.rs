//! The stored fields that the search screen loads per hit (ADR 0010, ADR 0012), and the popup
//! that chooses them. The index doesn't record which fields are stored, so every field is
//! offered.

use ratatui::Frame;
use ratatui::crossterm::event::KeyCode;
use ratatui::layout::Rect;
use ratatui::text::Line;

use crate::form::{FormEvent, render_popup, row};
use crate::protocol::index::IndexField;
use crate::text::escape_control;

/// Which fields the hits show, as `startSearch` sends them.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HitFields {
    names: Vec<String>,
    shown: Vec<bool>,
}

impl HitFields {
    /// Every field, in field number order.
    pub fn new(fields: &[IndexField]) -> Self {
        Self {
            names: fields.iter().map(|field| field.name.clone()).collect(),
            shown: vec![true; fields.len()],
        }
    }

    /// The fields to load, as `startSearch` takes them: `None` while every field is shown.
    pub fn param(&self) -> Option<Vec<String>> {
        if self.shown.iter().all(|shown| *shown) {
            return None;
        }
        Some(
            self.names
                .iter()
                .zip(&self.shown)
                .filter(|(_, shown)| **shown)
                .map(|(name, _)| name.clone())
                .collect(),
        )
    }

    /// How many fields the hits show, unless it is all of them, for the details panel.
    pub fn text(&self) -> Option<String> {
        let shown = self.shown.iter().filter(|shown| **shown).count();
        (shown < self.names.len()).then(|| match shown {
            0 => "none".to_owned(),
            1..=3 => self
                .param()
                .unwrap_or_default()
                .iter()
                .map(|name| escape_control(name))
                .collect::<Vec<_>>()
                .join(" · "),
            _ => format!("{shown} of {}", self.names.len()),
        })
    }
}

/// The popup: a draft of the choice, applied with Enter.
#[derive(Debug)]
pub struct FieldsForm {
    draft: HitFields,
    selected: usize,
}

impl FieldsForm {
    pub fn new(fields: &HitFields) -> Self {
        Self {
            draft: fields.clone(),
            selected: 0,
        }
    }

    /// ↑↓ select a field, Space shows or hides it, `a` shows all, `n` none; Enter applies, Esc
    /// cancels.
    pub fn handle_key(&mut self, key: KeyCode) -> FormEvent<HitFields> {
        let last = self.draft.names.len().saturating_sub(1);
        match key {
            KeyCode::Enter => return FormEvent::Apply(self.draft.clone()),
            KeyCode::Esc => return FormEvent::Cancel,
            KeyCode::Up | KeyCode::Char('k') => self.selected = self.selected.saturating_sub(1),
            KeyCode::Down | KeyCode::Char('j') => self.selected = (self.selected + 1).min(last),
            KeyCode::PageUp => self.selected = self.selected.saturating_sub(10),
            KeyCode::PageDown => self.selected = (self.selected + 10).min(last),
            KeyCode::Home | KeyCode::Char('g') => self.selected = 0,
            KeyCode::End | KeyCode::Char('G') => self.selected = last,
            KeyCode::Char(' ') | KeyCode::Left | KeyCode::Right => {
                if let Some(shown) = self.draft.shown.get_mut(self.selected) {
                    *shown = !*shown;
                }
            }
            KeyCode::Char('a') => self.draft.shown.fill(true),
            KeyCode::Char('n') => self.draft.shown.fill(false),
            _ => {}
        }
        FormEvent::None
    }

    /// The keys for the footer while the popup is open.
    pub fn keys() -> &'static str {
        " ⏎ apply   Esc cancel   ↑↓ select   Space show/hide   a all   n none"
    }

    pub fn render(&self, frame: &mut Frame, area: Rect) {
        let lines: Vec<Line> = self
            .draft
            .names
            .iter()
            .zip(&self.draft.shown)
            .enumerate()
            .map(|(i, (name, shown))| {
                let mark = if *shown { "[x]" } else { "[ ]" };
                row(&escape_control(name), mark, i == self.selected)
            })
            .collect();
        render_popup(
            frame,
            area,
            " Stored fields per hit ",
            lines,
            self.selected,
            40,
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::index::{DocValuesType, IndexOptions};

    fn fields(names: &[&str]) -> HitFields {
        let fields: Vec<IndexField> = names
            .iter()
            .map(|name| IndexField {
                name: (*name).to_owned(),
                number: 0,
                index_options: IndexOptions::None,
                has_norms: false,
                has_payloads: false,
                has_term_vectors: false,
                doc_values_type: DocValuesType::None,
                point_dimension_count: 0,
                point_num_bytes: 0,
                vector_dimension: 0,
                terms: None,
            })
            .collect();
        HitFields::new(&fields)
    }

    fn applied(mut form: FieldsForm) -> HitFields {
        match form.handle_key(KeyCode::Enter) {
            FormEvent::Apply(fields) => fields,
            other => panic!("expected Apply, got {other:?}"),
        }
    }

    #[test]
    fn every_field_is_loaded_until_one_is_hidden() {
        let all = fields(&["id", "title", "body", "price", "tags"]);
        assert_eq!(all.param(), None);
        assert_eq!(all.text(), None);

        let mut form = FieldsForm::new(&all);
        form.handle_key(KeyCode::Down);
        form.handle_key(KeyCode::Down);
        form.handle_key(KeyCode::Char(' '));
        let chosen = applied(form);
        assert_eq!(
            chosen.param(),
            Some(vec![
                "id".to_owned(),
                "title".to_owned(),
                "price".to_owned(),
                "tags".to_owned()
            ])
        );
        assert_eq!(chosen.text().as_deref(), Some("4 of 5"));
    }

    #[test]
    fn none_and_all() {
        let mut form = FieldsForm::new(&fields(&["id", "title", "body"]));
        form.handle_key(KeyCode::Char('n'));
        form.handle_key(KeyCode::End);
        form.handle_key(KeyCode::Char(' '));
        let one = applied(form);
        assert_eq!(one.param(), Some(vec!["body".to_owned()]));
        assert_eq!(one.text().as_deref(), Some("body"));

        let mut form = FieldsForm::new(&one);
        form.handle_key(KeyCode::Char('n'));
        assert_eq!(form.handle_key(KeyCode::Esc), FormEvent::Cancel);
        form.handle_key(KeyCode::Char('n'));
        let none = applied(form);
        assert_eq!(none.param(), Some(Vec::new()));
        assert_eq!(none.text().as_deref(), Some("none"));
        let mut form = FieldsForm::new(&none);
        form.handle_key(KeyCode::Char('a'));
        assert_eq!(applied(form).param(), None);
    }
}
