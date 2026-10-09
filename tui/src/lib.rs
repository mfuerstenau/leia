//! leia: a read-only Lucene index inspector.
//!
//! The terminal UI spawns `leia-server.jar` and talks JSON-RPC 2.0 to it over stdio.

pub mod analysis;
pub mod analyzer;
pub mod app;
pub mod build_info;
pub mod check;
pub mod cli;
pub mod client;
pub mod clipboard;
pub mod commits;
pub mod documents;
pub mod export;
pub mod form;
pub mod history;
pub mod hit_fields;
pub mod input;
pub mod jars;
pub mod java;
pub mod logo;
pub mod more_like_this;
pub mod overview;
pub mod parser_options;
pub mod prompt;
pub mod protocol;
pub mod scoring;
pub mod search;
pub mod server;
pub mod stored_scan;
pub mod terms;
pub mod text;
pub mod viewer;

#[cfg(test)]
mod test_support;
