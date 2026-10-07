//! leia: a read-only Lucene index inspector.
//!
//! The terminal UI spawns `leia-server.jar` and talks JSON-RPC 2.0 to it over stdio.

pub mod app;
pub mod build_info;
pub mod cli;
pub mod client;
pub mod clipboard;
pub mod commits;
pub mod documents;
pub mod input;
pub mod java;
pub mod overview;
pub mod parser_options;
pub mod prompt;
pub mod protocol;
pub mod search;
pub mod server;
pub mod terms;
pub mod text;
pub mod viewer;

#[cfg(test)]
mod test_support;
