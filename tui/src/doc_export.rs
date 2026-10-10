//! Exporting a document as JSON (ADR 0030): everything leia knows about it, put together in the
//! TUI from `getDocument`, `getStoredValue`, `getTermVector` and the rebuild job of ADR 0029. The
//! format is versioned and leia's own; its leaf values are the protocol's, binary as hex. The TUI
//! writes the file, so it checks the path as the server checks a terms export (ADR 0022): never
//! into an index directory (rule 1).

use std::collections::HashMap;
use std::fs::{self, OpenOptions};
use std::io::{ErrorKind, Write};
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::client::CallError;
use crate::documents::{flags, is_empty};
use crate::protocol::document::{
    DocValues, GetDocument, GetDocumentParams, GetDocumentResult, GetStoredValue,
    GetStoredValueParams, GetTermVector, GetTermVectorParams, ReconstructSource,
    ReconstructedField, StoredValue, TermVectorTerm,
};
use crate::protocol::index::{IndexField, IndexOptions};
use crate::server::Server;

/// The `format` of an export.
pub const FORMAT: &str = "leia-document";
/// The `version` of the format; a change to it, or to a protocol type in it, bumps it.
pub const VERSION: i32 = 1;

/// A document as leia exports it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ExportedDocument {
    /// Always [`FORMAT`].
    pub format: String,
    /// Always [`VERSION`].
    pub version: i32,
    /// The path that was opened.
    pub index: String,
    /// The index directory the document is in, as shown; `None` with only one.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub index_directory: Option<String>,
    pub doc_id: i32,
    pub deleted: bool,
    /// Whether the indexed terms were rebuilt; if not, no field has `indexed`.
    pub indexed_terms: bool,
    /// The fields in which the document has something, in field number order.
    pub fields: Vec<ExportedField>,
}

/// What the document has in one field.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ExportedField {
    pub name: String,
    /// The field number; `None` if the index doesn't know the field.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub number: Option<i32>,
    /// As the documents screen shows them: `I` indexed, `N` norm, `P` payloads, `S` stored, `V`
    /// term vector, `D` doc values, `T` points, `K` KNN vectors; `-` where absent.
    pub flags: String,
    /// What the postings of the field hold, over the whole index.
    pub index_options: IndexOptions,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub norm: Option<i64>,
    /// The stored values, in the order they were added, whole.
    pub stored: Vec<StoredValue>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub doc_values: Option<DocValues>,
    /// The term vector, in term order; `None` if the document has none in the field.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub term_vector: Option<Vec<TermVectorTerm>>,
    /// The terms the field was indexed with; `None` if the field isn't indexed or the terms
    /// weren't rebuilt.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub indexed: Option<IndexedTerms>,
}

/// The rebuilt terms of a field (ADR 0029).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct IndexedTerms {
    pub source: ReconstructSource,
    /// In term order, with their occurrences in the document.
    pub terms: Vec<TermVectorTerm>,
}

/// What the server says about a document, with every stored value whole: all of an export but
/// the rebuilt terms.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DocumentData {
    pub document: GetDocumentResult,
    /// The term vectors, by field name.
    pub term_vectors: HashMap<String, Vec<TermVectorTerm>>,
}

/// Where [`load`] gets a document from: the server, or a test's data.
pub trait Fetch {
    type Error;
    /// `getDocument`.
    fn document(&mut self, doc_id: i32) -> Result<GetDocumentResult, Self::Error>;
    /// `getStoredValue`.
    fn stored_value(
        &mut self,
        doc_id: i32,
        field: &str,
        index: i32,
    ) -> Result<StoredValue, Self::Error>;
    /// `getTermVector`.
    fn term_vector(&mut self, doc_id: i32, field: &str)
    -> Result<Vec<TermVectorTerm>, Self::Error>;
}

impl Fetch for Server {
    type Error = CallError;

    fn document(&mut self, doc_id: i32) -> Result<GetDocumentResult, CallError> {
        self.call::<GetDocument>(GetDocumentParams { doc_id })
    }

    fn stored_value(
        &mut self,
        doc_id: i32,
        field: &str,
        index: i32,
    ) -> Result<StoredValue, CallError> {
        let params = GetStoredValueParams {
            doc_id,
            field: field.to_owned(),
            index,
        };
        Ok(self.call::<GetStoredValue>(params)?.value)
    }

    fn term_vector(&mut self, doc_id: i32, field: &str) -> Result<Vec<TermVectorTerm>, CallError> {
        let params = GetTermVectorParams {
            doc_id,
            field: field.to_owned(),
        };
        Ok(self.call::<GetTermVector>(params)?.terms)
    }
}

/// Document `doc_id`, with every stored value that `getDocument` cuts fetched whole, and the
/// term vector of every field that has one.
pub fn load<F: Fetch>(fetch: &mut F, doc_id: i32) -> Result<DocumentData, F::Error> {
    let mut document = fetch.document(doc_id)?;
    let mut term_vectors = HashMap::new();
    for field in &mut document.fields {
        for (index, value) in (0..).zip(field.stored.iter_mut()) {
            if value.full_length.is_some() {
                *value = fetch.stored_value(doc_id, &field.name, index)?;
            }
        }
        if field.has_term_vector {
            term_vectors.insert(field.name.clone(), fetch.term_vector(doc_id, &field.name)?);
        }
    }
    Ok(DocumentData {
        document,
        term_vectors,
    })
}

/// The export of `data`, with the fields `schema` describes. `rebuilt` holds the indexed terms,
/// if they were rebuilt.
pub fn assemble(
    data: DocumentData,
    rebuilt: Option<Vec<ReconstructedField>>,
    schema: &HashMap<String, IndexField>,
    index: &str,
    index_directory: Option<String>,
) -> ExportedDocument {
    let indexed_terms = rebuilt.is_some();
    let mut rebuilt: HashMap<String, ReconstructedField> = rebuilt
        .into_iter()
        .flatten()
        .map(|field| (field.field.clone(), field))
        .collect();
    let DocumentData {
        document,
        mut term_vectors,
    } = data;
    let fields = document
        .fields
        .into_iter()
        .filter_map(|field| {
            let indexed = rebuilt.remove(&field.name);
            let has_terms = indexed.as_ref().is_some_and(|f| !f.terms.is_empty());
            if is_empty(&field) && !has_terms {
                return None;
            }
            let info = schema.get(&field.name);
            Some(ExportedField {
                number: info.map(|info| info.number),
                flags: flags(info, &field),
                index_options: info.map_or(IndexOptions::None, |info| info.index_options),
                term_vector: term_vectors.remove(&field.name),
                indexed: indexed.map(|indexed| IndexedTerms {
                    source: indexed.source,
                    terms: indexed.terms,
                }),
                name: field.name,
                norm: field.norm,
                stored: field.stored,
                doc_values: field.doc_values,
            })
        })
        .collect();
    ExportedDocument {
        format: FORMAT.to_owned(),
        version: VERSION,
        index: index.to_owned(),
        index_directory,
        doc_id: document.doc_id,
        deleted: document.deleted,
        indexed_terms,
        fields,
    }
}

/// `document` as pretty-printed JSON, ending in a line break.
pub fn to_json(document: &ExportedDocument) -> String {
    let mut json = serde_json::to_string_pretty(document).unwrap_or_default();
    json.push('\n');
    json
}

/// The file an export is offered to: `doc-<id>.json`.
pub fn default_file(doc_id: i32) -> String {
    format!("doc-{doc_id}.json")
}

/// Checks that an export may write `path`, as the server checks a terms export (ADR 0022): an
/// absolute path whose directory exists, outside and not below every index directory in
/// `index_dirs`, not a directory or a symbolic link, and not there yet unless `overwrite`. Real
/// paths are compared, so a link into an index directory is caught.
pub fn check_target(path: &Path, overwrite: bool, index_dirs: &[String]) -> Result<(), String> {
    if !path.is_absolute() {
        return Err(format!("The path must be absolute: {}", path.display()));
    }
    let parent = path
        .parent()
        .filter(|parent| parent.is_dir())
        .ok_or_else(|| {
            let parent = path.parent().unwrap_or(path);
            format!("No such directory: {}", parent.display())
        })?;
    let real_parent = fs::canonicalize(parent)
        .map_err(|error| format!("Cannot resolve {}: {error}", parent.display()))?;
    for dir in index_dirs {
        let real_dir = fs::canonicalize(dir).unwrap_or_else(|_| PathBuf::from(dir));
        if real_parent.starts_with(&real_dir) {
            return Err(format!(
                "Not writing into the index directory {dir}: leia never changes an index"
            ));
        }
    }
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_symlink() => Err(format!(
            "Not writing through a symbolic link: {}",
            path.display()
        )),
        Ok(metadata) if metadata.is_dir() => Err(format!("Is a directory: {}", path.display())),
        Ok(_) if !overwrite => Err(format!("File exists: {}", path.display())),
        _ => Ok(()),
    }
}

/// Writes `text` to `path` after [`check_target`]. If writing fails, what was written is deleted.
pub fn write_file(
    path: &Path,
    text: &str,
    overwrite: bool,
    index_dirs: &[String],
) -> Result<(), String> {
    check_target(path, overwrite, index_dirs)?;
    let mut options = OpenOptions::new();
    options.write(true);
    if overwrite {
        options.create(true).truncate(true);
    } else {
        options.create_new(true);
    }
    let mut file = options.open(path).map_err(|error| match error.kind() {
        ErrorKind::AlreadyExists => format!("File exists: {}", path.display()),
        _ => format!("Cannot create {}: {error}", path.display()),
    })?;
    file.write_all(text.as_bytes())
        .and_then(|()| file.sync_all())
        .map_err(|error| {
            let _ = fs::remove_file(path);
            format!("Cannot write {}: {error}", path.display())
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::document::{DocumentField, StoredType, TermPosition};
    use crate::protocol::index::DocValuesType;

    fn schema_field(name: &str, number: i32, index_options: IndexOptions) -> IndexField {
        IndexField {
            name: name.to_owned(),
            number,
            index_options,
            has_norms: index_options != IndexOptions::None,
            has_payloads: false,
            has_term_vectors: name == "body",
            doc_values_type: if name == "year" {
                DocValuesType::Numeric
            } else {
                DocValuesType::None
            },
            point_dimension_count: 0,
            point_num_bytes: 0,
            vector_dimension: 0,
            terms: None,
        }
    }

    fn schema() -> HashMap<String, IndexField> {
        [
            schema_field("id", 0, IndexOptions::Docs),
            schema_field("body", 1, IndexOptions::DocsAndFreqsAndPositionsAndOffsets),
            schema_field("labels", 2, IndexOptions::DocsAndFreqsAndPositions),
            schema_field("year", 3, IndexOptions::None),
            schema_field("empty", 4, IndexOptions::None),
        ]
        .into_iter()
        .map(|field| (field.name.clone(), field))
        .collect()
    }

    fn string(value: &str) -> StoredValue {
        StoredValue {
            kind: StoredType::String,
            value: value.to_owned(),
            binary: false,
            full_length: None,
        }
    }

    fn field(name: &str) -> DocumentField {
        DocumentField {
            name: name.to_owned(),
            norm: None,
            stored: Vec::new(),
            doc_values: None,
            has_term_vector: false,
        }
    }

    fn term(text: &str, position: i32, offsets: Option<(i32, i32)>) -> TermVectorTerm {
        TermVectorTerm {
            term: text.to_owned(),
            binary: false,
            freq: 1,
            positions: vec![TermPosition {
                position: Some(position),
                start_offset: offsets.map(|(start, _)| start),
                end_offset: offsets.map(|(_, end)| end),
                payload: None,
            }],
        }
    }

    /// Document 3: a stored id, a body with a term vector, unstored labels, doc values, and an
    /// empty field; `body` is cut.
    fn document() -> GetDocumentResult {
        GetDocumentResult {
            doc_id: 3,
            deleted: false,
            fields: vec![
                DocumentField {
                    stored: vec![string("doc-3")],
                    ..field("id")
                },
                DocumentField {
                    norm: Some(2),
                    stored: vec![StoredValue {
                        full_length: Some(9),
                        ..string("red b")
                    }],
                    has_term_vector: true,
                    ..field("body")
                },
                DocumentField {
                    norm: Some(1),
                    ..field("labels")
                },
                DocumentField {
                    doc_values: Some(DocValues {
                        kind: DocValuesType::Numeric,
                        numbers: vec![2024],
                        values: Vec::new(),
                    }),
                    ..field("year")
                },
                field("empty"),
            ],
        }
    }

    fn body_terms() -> Vec<TermVectorTerm> {
        vec![term("blue", 1, Some((4, 8))), term("red", 0, Some((0, 3)))]
    }

    fn rebuilt() -> Vec<ReconstructedField> {
        vec![
            ReconstructedField {
                field: "id".to_owned(),
                index_options: IndexOptions::Docs,
                source: ReconstructSource::Postings,
                terms: vec![TermVectorTerm {
                    term: "c3282d33".to_owned(),
                    binary: true,
                    freq: 1,
                    positions: Vec::new(),
                }],
            },
            ReconstructedField {
                field: "body".to_owned(),
                index_options: IndexOptions::DocsAndFreqsAndPositionsAndOffsets,
                source: ReconstructSource::TermVector,
                terms: body_terms(),
            },
            ReconstructedField {
                field: "labels".to_owned(),
                index_options: IndexOptions::DocsAndFreqsAndPositions,
                source: ReconstructSource::Postings,
                terms: vec![term("new", 0, None)],
            },
        ]
    }

    /// Document 3, or the first error of `failing`.
    struct Fake {
        failing: bool,
    }

    impl Fetch for Fake {
        type Error = String;

        fn document(&mut self, doc_id: i32) -> Result<GetDocumentResult, String> {
            assert_eq!(doc_id, 3);
            Ok(document())
        }

        fn stored_value(
            &mut self,
            doc_id: i32,
            field: &str,
            index: i32,
        ) -> Result<StoredValue, String> {
            assert_eq!((doc_id, field, index), (3, "body", 0));
            if self.failing {
                return Err("gone".to_owned());
            }
            Ok(string("red blue."))
        }

        fn term_vector(&mut self, doc_id: i32, field: &str) -> Result<Vec<TermVectorTerm>, String> {
            assert!(!self.failing, "no term vector after an error");
            assert_eq!((doc_id, field), (3, "body"));
            Ok(body_terms())
        }
    }

    fn loaded() -> DocumentData {
        load(&mut Fake { failing: false }, 3).unwrap()
    }

    #[test]
    fn loads_cut_values_whole_and_the_term_vectors() {
        let data = loaded();
        assert_eq!(data.document.fields[1].stored, vec![string("red blue.")]);
        assert_eq!(data.document.fields[0].stored, vec![string("doc-3")]);
        assert_eq!(data.term_vectors.len(), 1);
        assert_eq!(data.term_vectors["body"], body_terms());
    }

    #[test]
    fn loading_stops_at_the_first_error() {
        assert_eq!(load(&mut Fake { failing: true }, 3), Err("gone".to_owned()));
    }

    #[test]
    fn writes_the_golden_file() {
        let export = assemble(loaded(), Some(rebuilt()), &schema(), "/i/demo", None);
        assert_eq!(
            to_json(&export),
            include_str!("../tests/fixtures/document-export.json")
        );
        let parsed: ExportedDocument =
            serde_json::from_str(include_str!("../tests/fixtures/document-export.json")).unwrap();
        assert_eq!(parsed, export);
    }

    #[test]
    fn leaves_out_empty_fields_and_says_whether_terms_were_rebuilt() {
        let export = assemble(loaded(), None, &schema(), "/i", Some("b".to_owned()));
        assert!(!export.indexed_terms);
        assert_eq!(export.index_directory.as_deref(), Some("b"));
        let names: Vec<&str> = export.fields.iter().map(|f| f.name.as_str()).collect();
        assert_eq!(names, ["id", "body", "labels", "year"]);
        assert!(export.fields.iter().all(|field| field.indexed.is_none()));
        let json = to_json(&export);
        assert!(json.contains("\"indexDirectory\": \"b\""), "{json}");
        assert!(json.contains("\"indexedTerms\": false"), "{json}");
    }

    #[test]
    fn keeps_a_field_that_only_has_indexed_terms() {
        let mut data = loaded();
        data.document.fields[2].norm = None;
        let without = assemble(data.clone(), None, &schema(), "/i", None);
        assert!(!without.fields.iter().any(|field| field.name == "labels"));

        let with = assemble(data, Some(rebuilt()), &schema(), "/i", None);
        let labels = with.fields.iter().find(|f| f.name == "labels").unwrap();
        assert_eq!(labels.flags, "I-------");
        assert_eq!(
            labels.indexed.as_ref().unwrap().source,
            ReconstructSource::Postings
        );
    }

    #[test]
    fn has_no_number_for_a_field_the_index_does_not_know() {
        let export = assemble(loaded(), None, &HashMap::new(), "/i", None);
        assert_eq!(export.fields[0].number, None);
        assert_eq!(export.fields[0].index_options, IndexOptions::None);
        assert_eq!(export.fields[0].flags, "---S----");
    }

    #[test]
    fn rejects_unknown_formats_of_its_own() {
        let json = include_str!("../tests/fixtures/document-export.json").replacen(
            "\"deleted\"",
            "\"extra\": 1, \"deleted\"",
            1,
        );
        assert!(serde_json::from_str::<ExportedDocument>(&json).is_err());
    }

    fn temp_dir(name: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("leia-doc-export-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn checks_the_target() {
        let dir = temp_dir("check");
        let index = dir.join("index");
        fs::create_dir_all(index.join("sub")).unwrap();
        let indexes = [index.display().to_string()];
        let out = dir.join("doc-3.json");

        assert_eq!(check_target(&out, false, &indexes), Ok(()));
        assert_eq!(
            check_target(Path::new("doc-3.json"), false, &indexes),
            Err("The path must be absolute: doc-3.json".to_owned())
        );
        let missing = dir.join("missing").join("doc.json");
        assert_eq!(
            check_target(&missing, false, &indexes),
            Err(format!(
                "No such directory: {}",
                dir.join("missing").display()
            ))
        );
        let refused = format!(
            "Not writing into the index directory {}: leia never changes an index",
            index.display()
        );
        for inside in [index.join("doc.json"), index.join("sub").join("doc.json")] {
            assert_eq!(check_target(&inside, true, &indexes), Err(refused.clone()));
        }
        assert_eq!(
            check_target(&index, true, &[]),
            Err(format!("Is a directory: {}", index.display()))
        );

        fs::write(&out, "old").unwrap();
        assert_eq!(
            check_target(&out, false, &indexes),
            Err(format!("File exists: {}", out.display()))
        );
        assert_eq!(check_target(&out, true, &indexes), Ok(()));
        fs::remove_dir_all(dir).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn catches_symbolic_links() {
        let dir = temp_dir("links");
        let index = dir.join("index");
        fs::create_dir_all(&index).unwrap();
        let indexes = [index.display().to_string()];
        let into_index = dir.join("into");
        std::os::unix::fs::symlink(&index, &into_index).unwrap();
        assert!(
            check_target(&into_index.join("doc.json"), false, &indexes)
                .unwrap_err()
                .starts_with("Not writing into the index directory")
        );

        let link = dir.join("link.json");
        std::os::unix::fs::symlink(dir.join("elsewhere.json"), &link).unwrap();
        assert_eq!(
            check_target(&link, true, &indexes),
            Err(format!(
                "Not writing through a symbolic link: {}",
                link.display()
            ))
        );
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn writes_and_overwrites_a_file() {
        let dir = temp_dir("write");
        let out = dir.join("doc.json");
        assert_eq!(write_file(&out, "one", false, &[]), Ok(()));
        assert_eq!(fs::read_to_string(&out).unwrap(), "one");
        assert_eq!(
            write_file(&out, "two", false, &[]),
            Err(format!("File exists: {}", out.display()))
        );
        assert_eq!(write_file(&out, "2", true, &[]), Ok(()));
        assert_eq!(fs::read_to_string(&out).unwrap(), "2");
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn names_the_file_after_the_document() {
        assert_eq!(default_file(42), "doc-42.json");
    }
}
