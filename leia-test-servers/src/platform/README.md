The `lib/platform/` of the fake Elasticsearch in `build/fake-server-10/` (ADR 0090), where a real one
has a folder of native libraries per platform, such as `linux-x64/`. Its postings format checks that
Elasticsearch's `LoaderHelper` is given a folder in it.
