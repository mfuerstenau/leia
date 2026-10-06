//! leia: a read-only Lucene index inspector.
//!
//! The terminal UI spawns `leia-server.jar` and talks JSON-RPC 2.0 to it over stdio.

pub mod build_info;
pub mod cli;
pub mod client;
pub mod java;
pub mod overview;
pub mod protocol;
pub mod server;

#[cfg(test)]
mod test_support;
