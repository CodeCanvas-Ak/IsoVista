# How to Implement a New Checker

This guide describes the **current** checker API. Proposed unified result states and cross-checker protocols are maintained in [History Checkers](plans/06-history-checkers.md).

## 1. Implement the interface

Create a class under `src/main/java/checker/` that implements `Checker`, exposes a unique static `NAME`, and provides a `Properties` constructor:

```java
public class ExampleChecker implements Checker<Long, Long> {
    public static final String NAME = "EXAMPLE";

    public ExampleChecker(Properties config) {
        // Read checker-specific settings and initialize resources.
    }
}
```

`Main` discovers the implementation through reflection and constructs it with the current `Properties` object.

## 2. Implement the three methods

### `verify`

```java
boolean verify(History<Long, Long> history);
```

Return `true` when the input satisfies the checker's specification and `false` when a violation is found. Document the supported isolation level, data model, initialization rules, duplicate-write behavior, and any completeness assumptions. Invalid or unsupported input should fail explicitly rather than being reported as a semantic violation.

For JNI or subprocess implementations, preserve the original output and error stream. When starting a subprocess, register its PID so runtime monitoring can include it:

```java
RuntimeInfoRecorder.addPid(process.pid());
```

### `outputDotFile`

```java
void outputDotFile(String path);
```

After a violation, write the counterexample graph to the supplied path. The backend parses DOT with pydot, and `BugView.vue` consumes the resulting nodes and edges. Preserve the attributes expected by `GET /view/{bug_id}`, including node/edge IDs, labels, `ops`, `relate_to`, `style`, and `in_cycle` where applicable. Optional attributes may be absent—for example, a single-transaction node has no `relate_to`—and the API will return empty values instead of failing the whole view.

DOT output is optional: throw the project's `NotImplementedException` when the checker cannot produce a meaningful graph. `Main` will still archive the original bug history and continue the run. A missing or unparsable graph is shown as an empty graph with an explanation; it must not be replaced with a fabricated DOT file.

If SI or SER explanation utilities apply, see the existing `AnomalyInterpreter` usage under `src/main/java/checker/`. Validate both graph syntax and the Web view; generating a DOT file alone is not enough.

### `getProfileInfo`

```java
Map<String, Long> getProfileInfo();
```

Return stage durations in milliseconds. Use the existing names where applicable:

- Solver-based checkers: `Construction`, `Encoding`, `Pruning`, `Solving`.
- Graph-based checkers: `Construction`, `Traversal`.

Do not report missing measurements as zero. Keep total runtime, stage timing, and output I/O distinguishable for later performance analysis.

## 3. Register the configuration

Add an unambiguous `checker.isolation` entry in `util/ConfigParser.java`, mapping the configuration value to the checker `NAME` and the intended `IsolationLevel`. Review the existing mappings instead of copying them blindly; the current Elle TCC enum mapping is already listed as a risk in `ROADMAP.md`.

If the checker should be selectable in the UI, update `frontend/src/components/Configuration.vue` and verify the submitted configuration against the Java parser.

## 4. Add regression coverage

Under `src/test/java/`, add at least:

- A minimal accepting history and a minimal rejecting history.
- Boundary cases for initialization, duplicate writes, aborted operations, and the supported data model.
- Counterexample DOT generation and parsing checks.
- Stage profile presence and units.
- For external tools: non-zero exit, malformed output, timeout, and missing dependency behavior.

Run the focused test with JDK 11, then the full relevant verifier suite. Record native libraries, solver versions, and any tests that could not be executed.
