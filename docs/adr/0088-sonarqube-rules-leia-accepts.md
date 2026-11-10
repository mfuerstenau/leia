# ADR 0088 — SonarQube rules leia accepts

- **Status:** Accepted
- **Date:** 2026-10-18

## Context

The Sonar fix of 2026-10-18 (`88ea82a`, `8b696a4`) left 642 issues in 34 rules on the main code. Some rules were skipped on purpose, some findings are false positives, and some were never decided. Nothing is recorded in SonarQube, and the `sonar-check` skill runs it in a fresh container, so every scan counts them all again and the quality gate can't pass. On new code the gate needs reliability A, so no issue typed BUG at all, and a low debt ratio.

The first item of "SonarQube quality gate" in `docs/tasklist.md` decides, for every open rule, whether to fix it or to accept it. This ADR records the rules that are accepted, with the scope and the reason for each. How they are whitelisted, so that the decision lives in the repository and applies to a new container, is the second item, and the section "Mechanism" below.

## Decision

### Fixed

These were fixed in the code, without a change in behavior:

- **S3655** (Optional `get()` without a check Sonar can follow, BUG): each `x.a().isPresent()` … `x.a().get()` reads the `Optional` into a local first, or goes through `map`, `flatMap`, `filter` or `ifPresent`.
- **S2259** (null dereference, BUG, false positive): the `equals` of the More-Like-This queries binds `other` with `instanceof` instead of casting it; `IndexParts.open` keeps the failures in a list.
- **S3039** (string bounds, BUG, false positive): `TextFormat.cut` drops the last code point by its `charCount`.
- **S2388**, **S3457**, **S5411**, **S1141**, **S127**, **S3398**, **S6326**: the inner-class `maxDoc()` calls name `super`, log messages are lambdas and run-time format patterns are named locals, boxed `Boolean`s are unboxed explicitly, nested `try`s are helper methods, the code-point loops are `while` loops, private helpers sit in the inner class that calls them, and regular expressions use `{3}`.

### Accepted

| Rule | Scope | How | Reason |
|---|---|---|---|
| S6878 record patterns | all | config | House style: a binding with accessor calls reads as well as a record pattern, and converting 269 sites is churn without a gain in behavior. A pattern could name every component, or skip one with `var _` or a typed `long _`, which Palantir 2.102.0 formats |
| S7475 unused record components in patterns | `…tui.check.Check` | config | The fix is a bare `_` as a component (`R(long id, _)`), which Palantir Java Format 2.102.0 can't format (`expected token: '_'`); `var _` and `case R _` it can |
| S1452 wildcard return types | leia-server `HandlerModule` | config | Dagger's `@IntoSet MethodHandler<?, ?>` and the `RpcMethod<?, ?>` registry need them |
| S108 empty block | leia-tui | config | `default -> {}` is the idiom of a key switch that ignores the other keys |
| S2094 empty class | leia-protocol, the empty `*Params` records and variants | config | Hard rule 3: every method has an explicit params record, also one without fields |
| S106 standard output | `Main`, `LeiaLog` | `@SuppressWarnings` | The CLI's own output (`--version`, `--download-runtimes`, the internal error), and the fallback for when the log can't be opened |
| S1181 catching `Error` | `LeiaBackend` | `@SuppressWarnings` | A `LinkageError` from a jar must still complete the call's future |
| S3077 `volatile` reference (BUG) | `ExtensionJars`, `JarChain`, `RuntimeFolders`, `Cancellation`, `Session` | `@SuppressWarnings` | Immutable values, written under a lock and published through `volatile`, which is correct as it is |
| S2143 `java.util.Date` | the date-range code of core and the runtimes | config | Lucene's `DateTools` takes a `Date` |
| S6218 array in a record (BUG) | the 13 records with an array component in runtime, core, server and tui | `@SuppressWarnings` | Data carriers that are never compared, hashed or used as keys (checked with a grep for `equals`, `Set` and `Map` keys and `distinct`). One that ever is gets its own `equals` and `hashCode` |
| S2975, S1182 `clone` | `CheckDirectory`, `Lucene3/5/7/9Check` | `@SuppressWarnings` | Lucene's `IndexInput.clone` contract, which a wrapping input has to keep |
| S2095 resource not closed (BUG, false positive) | the 12 sites | `@SuppressWarnings` | Ownership passes to the caller, or to a part or open index that is closed elsewhere |
| S2583 unreachable code (BUG, false positive) | `Lucene3/5/7/9Index` | `@SuppressWarnings` | `openIfChanged` returns null when nothing changed |
| S2386 public static mutable field | `RuntimeFolders.KNOWN` | `@SuppressWarnings` | A `List.copyOf`, which is immutable |
| S2326 unused type parameter | `DocValuesMatch`, `runtime.DocValuesSearch.Match`, `Commits.Fetched`, `widgets.Form.Event` | `@SuppressWarnings` | Each variant of the sealed interface uses it; the parameter ties them to one type, as `Event<Settings>` does |
| S6916 `when` guard | leia-tui | config | The hits are switches over enum or char constants, and `when` is allowed only on pattern labels |
| S135 several `break`/`continue` | the 6 loops | `@SuppressWarnings` | Rewriting them makes them harder to read |
| S107 too many parameters | the 10 methods | `@SuppressWarnings` | Separate options of a search, highlight or recall call; a record built only to carry them adds no meaning |
| S6539 monster class | `Documents`, `Search` | `@SuppressWarnings` | Screen classes that wire their own forms and popups |
| S3400 method returns a constant | `FieldsForm`, `ScoringForm` | `@SuppressWarnings` | Matches the `keys()` of the other forms |
| S110 inheritance depth | `Searcher` | `@SuppressWarnings` | It extends Lucene's class hierarchy |
| S1479 many `case`s | `Session` | `@SuppressWarnings` | The dispatch over the sealed `Action` |
| S6474 dependency verification | the build | settled by ADR 0089 | Was accepted for now; `gradle/verification-metadata.xml` fixes it |

After the fixes, the main code has 524 open issues in 24 rules, all of them in this table. "How" says how each is whitelisted (see "Mechanism"): config is an entry in `.sonar-check.properties`, `@SuppressWarnings` an annotation at each site. S6474 was left open and is settled by ADR 0089, see below.

### Duplication is measured, not gated

The gate's condition on duplicated lines in new code (`new_duplicated_lines_density`, at most 3 %) is left out of leia's gate: after the fixes above it was the only one to fail, at 20.1 %. Most of the duplication is in the Lucene 5, 7 and 9 runtimes, which repeat the same code on purpose: each compiles against its own Lucene API and runs in a class loader of its own (ADR 0073), so one shared copy can't serve them all. Duplication is still measured and shown in the `sonar-check` report.

`.sonar-check.properties` at the root of the build says so (`qualityGate.without=new_duplicated_lines_density`). On each scan the skill rebuilds the gate `sonar-check leia` from SonarQube's "Sonar way" without the listed conditions and attaches it to the project, so it applies to a new container too. Other builds the skill scans keep "Sonar way".

### leia-test-servers is not analysed

`leia-test-servers` is not analysed. The scanner reads only the `main` and `test` source sets, and the module has neither. Its Java is in the source sets `server9`, `server9Ext`, `server9Knn`, `server10` and `server90`, which build the fake servers' jars and indexes (ADR 0090, ADR 0092). The module is a test fixture: it is in neither `leia.jar` nor the zip, part of it stands in for Elasticsearch and OpenSearch classes whose shape the real servers dictate, and the `serverTest` tasks of leia-server and leia-tui exercise it. An 85 % bar on its own tests would measure nothing. No build or `.sonar-check.properties` setting is needed for this. If the module ever gets a `main` or `test` source set, it is checked like the other modules.

### Mechanism

The accepted rules are whitelisted in the repository, in two ways, by the kind of decision:

- **A broad decision** (a house style, a tool's limit, a framework, a whole module or file) is a `sonar.issue.ignore.multicriteria` entry in `.sonar-check.properties`: a rule key and a file pattern, with a comment naming its row above. Such a decision covers the code that is written later too: a new S6878 binding, a `default -> {}` in leia-tui or a new empty `*Params` record doesn't fail the gate.
- **A single-site judgment** (a false positive, an intentional exception) is `@SuppressWarnings("java:S…")` on the narrowest element that holds the issue: the local variable, the field, the method or the type, with the reason in a `//` comment on the line above (Palantir keeps it there). The rule still reports a new instance elsewhere in the same file, so a real resource leak or a new `volatile` field isn't hidden by an old judgment.

The `sonar-check` skill passes every `sonar.*` key of `.sonar-check.properties` to the scanner, after its own defaults, so the entries apply in a new container as well; the scanner drops the issues they match before they reach the server, and the report says how many entries there are. A baseline is analyzed with the file of its own commit.

**S6474** (dependency verification) stays open: it is an issue of the project, not of a file, and no `resourceKey` matches it (`**/*` was tried). It can't fail the gate, since a baseline always has it already. ADR 0089 settles it: with `gradle/verification-metadata.xml` in place the issue is gone, and the main code has no open issues.

## Alternatives considered

- **Fixing everything.** S7475 can't be fixed while Palantir can't format a bare `_`, and S6878 would rewrite 269 sites for style alone; S1452, S2094 and S2975 are required by Dagger, hard rule 3 and Lucene; the BUG false positives would need code that is worse to read. Rejected.
- **Accepting S3655 as a false positive.** Most of its sites are correct, but 73 BUG findings keep the gate's reliability at E, and the rewrites are small. Rejected.
- **Only `sonar.issue.ignore.multicriteria`.** Its pattern is a file, so a false positive at one site would hide the whole rule in that file, BUG rules such as S2095 included. Rejected for single sites.
- **Only `@SuppressWarnings`.** The house-style rules alone (S6878, S108, S1452) have over 400 issues, each of which would need an annotation, noise that every new site would have to repeat. Rejected for broad decisions.
- **A quality profile kept in the repository and restored into the container.** Rule-wide only, so no scope per module or file; a third format beside the properties and the annotations; and a larger change to the skill. Rejected.
- **"Accept" or "False positive" in SonarQube.** Lost with each new container, and not in the repository. Rejected.
