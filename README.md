# MARS Dual-Mode Pipelining and Pipeline Visualizer

This project extends the MARS MIPS Assembler and Runtime Simulator with a cycle-based five-stage pipeline execution mode and an educational Pipeline Visualizer tool.

Original MARS is still preserved for normal instruction-by-instruction execution. The added work introduces a second execution model that lets students observe how MIPS instructions move through a pipelined datapath cycle by cycle.

## What We Added

- Dual execution support:
  - Classic mode: original MARS behavior.
  - Pipelined mode: cycle-based five-stage pipeline simulation.
- A Tools-menu Pipeline Visualizer integrated with the MARS tool system.
- A lecture-style pipeline timing diagram with instructions as rows and cycles as columns.
- Live IF, ID, EX, MEM, and WB stage panels.
- Cycle event badges for normal execution, forwarding, stalls, and flushes.
- Plain-English cycle explanations for students.
- Replay Cycle support to rewatch the latest visual event without re-executing program state.
- One-click demo loading from the visualizer for pipeline test cases.
- Focused test programs for forwarding, load-use stalls, branch flushes, and store forwarding.

## Pipeline Model

The pipelined execution mode models the standard five-stage MIPS pipeline:

| Stage | Meaning |
|---|---|
| IF | Instruction Fetch |
| ID | Instruction Decode / Register Read |
| EX | Execute / ALU |
| MEM | Memory Access |
| WB | Write Back |

The simulator tracks pipeline state across cycles and exposes stage snapshots to the visualizer. It also reports cycle events such as forwarding, stalls, and branch flushes.

## Hazard Handling

The project focuses on the main hazards taught in an introductory computer architecture course:

| Hazard / Event | Visualizer Behavior |
|---|---|
| ALU data dependency | Shows forwarding paths and marks EX with forwarding. |
| Load-use dependency | Inserts and displays a stall/bubble. |
| Branch or jump taken | Flushes wrong-path instructions. |
| Store data dependency | Demonstrates forwarding into store behavior. |

The goal is educational clarity rather than full industrial CPU modeling.

## Pipeline Visualizer

Open the tool from:

```text
Tools -> Pipeline Visualizer
```

The visualizer provides:

- A header showing cycle count, retired instruction count, and next PC.
- An event badge showing `NORMAL`, `FORWARD`, `STALL`, or `FLUSH`.
- A live five-stage pipeline view.
- Per-stage detail panes explaining what each stage is doing.
- A timing diagram that resembles classroom pipeline diagrams.
- An explanation panel describing the latest cycle.
- A Replay Cycle button.
- Sample buttons for quickly loading demonstration programs.

## Demo Programs

The main demonstration files are in the `test/` directory:

| File | Demonstrates |
|---|---|
| `test/forwarding.asm` | ALU dependency forwarding |
| `test/loadusestall.mips` | Load-use stall and bubble insertion |
| `test/branchflush.asm` | Branch control hazard and flush |
| `test/storeforwarding.asm` | Store forwarding behavior |

Older pipeline test files may also exist in `pipeline_tests/` depending on branch history, but the current visualizer demo flow uses the `test/` directory.

## How To Use

1. Launch `Mars.jar`.
2. Open `Tools -> Pipeline Visualizer`.
3. Use a sample button or open a MIPS program manually.
4. Assemble the program.
5. Connect the Pipeline Visualizer.
6. Use `Run -> Step Cycle` to advance one pipeline cycle at a time.
7. Watch the timing diagram, stage panes, and explanation panel.
8. Use `Replay Cycle` to rewatch important forwarding, stall, or flush events.

## Documentation

Additional project documentation:

- `PIPELINE_VISUALIZER_GUIDE.md`: usage guide and pipelining explanation.
- `PIPELINE_PROJECT_REPORT_CONTEXT.md`: report-writing context and project summary.

## Scope and Limitations

The pipeline mode supports an educational subset of MIPS instructions suitable for demonstrating integer arithmetic, memory operations, branches, jumps, syscalls, forwarding, stalls, and flushes.

Unsupported instructions may still work in classic MARS mode, but may be rejected by the pipelined simulator. This is intentional for the current project scope.

## Base Project

This repository is based on the original MARS project by Pete Sanderson and Kenneth Vollmar. MARS is a lightweight IDE for programming and simulating MIPS assembly language.

Original MARS website:

```text
https://dpetersanderson.github.io/
```
