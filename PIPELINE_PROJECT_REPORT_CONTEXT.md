# Pipeline Visualizer Project Context

This document gives report-writing context for the MARS Dual Mode Pipelining project. It explains what was added, why it was added, what the final tool does, and how it can be described in a project report without going too deep into source code.

The companion document `PIPELINE_VISUALIZER_GUIDE.md` explains pipelining concepts and how to use the interface. This document focuses more on the project story and final feature set.

## 1. Project Overview

The project extends MARS, the MIPS Assembler and Runtime Simulator, by adding a cycle-based pipeline execution mode and an educational pipeline visualizer tool.

Original MARS executes programs instruction by instruction. That is useful for basic assembly programming, but it does not show how a pipelined processor overlaps instructions across multiple stages. This project adds a teaching-oriented pipeline model so students can observe pipeline behavior cycle by cycle.

The final result is a MARS tool called:

```text
Pipeline Visualizer
```

It appears in the MARS Tools menu like the other built-in MARS tools.

## 2. Main Goal

The main goal is to help students understand five-stage MIPS pipelining visually.

The tool is designed to show:

- How instructions move through IF, ID, EX, MEM, and WB
- How multiple instructions overlap in different pipeline stages
- How forwarding avoids some stalls
- Why load-use hazards require a stall
- Why taken branches or jumps cause flushes
- How the pipeline changes from one cycle to the next

The focus is educational clarity rather than implementing every possible MIPS instruction or every advanced processor feature.

## 3. Why This Project Was Needed

MARS is widely used for MIPS assembly programming, but classic MARS does not provide a built-in pipeline execution view. Students can step through instructions, but they cannot easily see:

- Which instruction is currently in each pipeline stage
- What happens in one clock cycle
- Where bubbles are inserted
- When forwarding occurs
- Which instructions are flushed after a branch

Because pipelining is usually taught using timing diagrams, students need a visual representation that matches classroom material. This project bridges the gap between writing MIPS code and understanding how that code would flow through a pipelined datapath.

## 4. Development Direction

At first, the pipeline visualization was embedded inside the normal MARS message area. That version showed basic pipeline information but did not fit naturally with the existing MARS tool system.

The project was then changed so that the visualizer exists as a proper MARS tool, similar to existing tools in the Tools menu.

The old embedded visualizer was removed. The final version keeps only the clean Tools-menu version.

This makes the feature more consistent with MARS design because tools in MARS are normally launched separately and connected to the running program.

## 5. Final Architecture in Simple Terms

The project has two main parts:

1. Pipeline execution model
2. Pipeline visualizer tool

The execution model handles the actual cycle-by-cycle simulation.

The visualizer tool displays the current pipeline state in a way students can understand.

In simple terms:

```text
MIPS Program -> Pipeline Simulator -> Pipeline Visualizer UI
```

The simulator produces information about the current cycle. The visualizer reads that information and updates the diagrams, stage panes, event messages, and timing table.

## 6. Dual Execution Mode

The project supports two execution modes:

| Mode | Description |
|---|---|
| Classic mode | Normal MARS instruction-by-instruction execution |
| Pipelined mode | New cycle-based five-stage pipeline execution |

Classic mode preserves the original MARS behavior.

Pipelined mode is used when the Pipeline Visualizer is connected. It advances the program one pipeline cycle at a time rather than one complete instruction at a time.

This is why the project can be described as "dual mode": it keeps the original MARS execution model and adds a new pipeline execution model.

## 7. Pipeline Model Implemented

The pipeline model is based on the standard five-stage MIPS pipeline:

| Stage | Meaning |
|---|---|
| IF | Instruction Fetch |
| ID | Instruction Decode / Register Read |
| EX | Execute / ALU |
| MEM | Data Memory Access |
| WB | Register Write Back |

The simulator tracks instructions as they move through these stages. Each cycle, instructions shift forward unless a hazard causes special behavior.

## 8. Supported Educational Behaviors

The pipeline simulator supports the most important behaviors needed for an educational demonstration:

### Normal Pipeline Flow

Instructions enter the pipeline one after another. After the pipeline fills, multiple instructions are active at the same time.

### Forwarding

Forwarding is shown when an instruction uses a result before it has been written back to the register file.

The visualizer can show:

- EX/MEM to EX forwarding
- MEM/WB to EX forwarding

### Load-Use Stall

When an instruction immediately after a load needs the loaded value, one stall cycle is inserted.

The visualizer marks this with a stall event and explains why the pipeline had to wait.

### Branch / Jump Flush

When a branch or jump is taken, younger wrong-path instructions are flushed.

The visualizer marks this as a flush event and explains that the PC was redirected.

### Syscall Handling

The simulator includes support for simple syscall-based test programs, such as printing an integer and exiting.

## 9. Current Scope and Limitations

The pipeline model is intentionally focused on a useful teaching subset of MIPS instructions.

It is not meant to be a fully complete industrial CPU simulator. Instead, it is meant to demonstrate the main concepts from a computer architecture course.

The supported subset is enough for:

- Arithmetic examples
- Register dependencies
- Load/store examples
- Branch examples
- Simple output using syscalls

If an unsupported instruction is used in pipelined mode, MARS can report it as unsupported for the pipeline simulator. This is acceptable for the project scope because the goal is educational visualization, not full ISA coverage.

## 10. Final User Interface

The final Pipeline Visualizer UI was redesigned to look more like a classroom pipeline timing diagram.

The window is larger than the original version and has multiple panes:

1. Header and controls
2. Event badge
3. Live pipeline stage view
4. Five stage detail panes
5. Pipeline timing diagram
6. Explanation panel

## 11. Header and Controls

The header shows:

- Current cycle number
- Number of retired instructions
- Next PC

The UI includes:

| Control | Purpose |
|---|---|
| Replay Cycle | Replays the latest visual cycle without executing the program again |
| Clear Timeline | Clears the visual timeline |

The Replay Cycle feature is important because students may miss what happened during a cycle. They can replay the animation and explanation safely.

## 12. Event Badge

The event badge summarizes the latest cycle:

| Badge | Meaning |
|---|---|
| NORMAL | Normal pipeline movement |
| FORWARD | Forwarding occurred |
| STALL | A stall was inserted |
| FLUSH | Instructions were flushed |

This gives students a quick way to identify important pipeline events.

## 13. Live Stage View

The live stage view shows the five pipeline stages:

```text
IF -> ID -> EX -> MEM -> WB
```

Each stage shows the instruction currently in that stage. Bubbles are shown when no useful instruction occupies a stage.

Forwarding is shown with dashed visual paths. Stalls and flushes are shown with different colors.

## 14. Five Stage Detail Panes

The interface includes one pane for each stage:

```text
IF | ID | EX | MEM | WB
```

Each pane gives:

- The current instruction
- The instruction PC
- A short explanation of what that stage is doing

This helps students understand not only where an instruction is, but what work is being done in that stage.

## 15. Pipeline Timing Diagram

The timing diagram is the main educational feature.

Rows represent instructions.

Columns represent cycles.

Each cell shows the stage occupied by that instruction in that cycle.

Example:

```text
Instruction              C1   C2   C3   C4   C5
addi $t0,$zero,5         IF   ID   EX   MEM  WB
addi $t1,$zero,7              IF   ID   EX   MEM
add $t2,$t0,$t1                    IF   ID   EX*
```

The `EX*` marker means the instruction used forwarding during EX.

This diagram makes it easy to see instruction overlap, stalls, and flushes over time.

## 16. Explanation Panel

The explanation panel describes the latest cycle in plain English.

Examples:

```text
Normal pipeline movement. Each in-flight instruction advances to the next stage.
```

```text
A load-use hazard was detected. The instruction in ID needs the loaded register before the load can write it back, so one bubble is inserted.
```

```text
A branch or jump was taken. Younger instructions are flushed and the next PC is redirected.
```

This makes the visualizer more useful for beginners because they do not need to interpret the diagram alone.

## 17. Replay Cycle Feature

The Replay Cycle button replays the most recent visual event.

It does not execute the MIPS program again.

This is important because re-executing a cycle could incorrectly change registers or memory. Instead, the tool stores the latest visual snapshot and replays the animation/explanation only.

This feature is useful during demos because the presenter can pause and replay a forwarding, stall, or flush event for the professor.

## 18. Removed Earlier Embedded Version

Earlier in development, there was an embedded pipeline panel inside the MARS messages area.

That version was removed because the final requirement was to make the visualizer a proper MARS tool.

The final jar contains only the tool-based visualizer.

This keeps the interface cleaner and avoids having two separate pipeline displays.

## 19. Test Programs

Three test programs were added to demonstrate important cases:

| Program | Purpose |
|---|---|
| `pipeline_tests/01_forwarding.asm` | Demonstrates forwarding |
| `pipeline_tests/02_load_use_stall.asm` | Demonstrates load-use stall |
| `pipeline_tests/03_branch_flush.asm` | Demonstrates branch flush |

These are useful for report screenshots, demo preparation, and explaining the feature set.

## 20. Suggested Report Structure

A good report can be organized like this:

1. Introduction
2. Problem statement
3. Background on MIPS pipelining
4. Existing MARS limitation
5. Proposed solution
6. Pipeline execution model
7. Pipeline visualizer UI
8. Hazard handling
9. Test cases and demonstration
10. Limitations
11. Conclusion

## 21. Suggested Problem Statement

MARS is useful for learning MIPS assembly, but it does not visually demonstrate how a pipelined processor executes multiple instructions at the same time. Students studying computer architecture often learn pipelining through timing diagrams, forwarding paths, stalls, and flushes, but these concepts are difficult to connect with actual assembly programs. This project adds a pipeline execution mode and a visualizer tool to make pipeline behavior observable cycle by cycle.

## 22. Suggested Solution Summary

The project adds a five-stage cycle-based pipeline simulator to MARS and connects it to a new Tools-menu Pipeline Visualizer. The visualizer displays the current state of IF, ID, EX, MEM, and WB, shows an instruction-vs-cycle timing diagram, highlights forwarding, stalls, and flushes, and explains each cycle in plain English. This helps students understand how MIPS instructions overlap inside a pipeline.

## 23. Suggested Conclusion

The project successfully extends MARS from a simple instruction-level simulator into a dual-mode educational simulator with pipeline visualization. The final tool helps students observe pipeline behavior interactively, including instruction overlap, forwarding, load-use stalls, and branch flushes. By combining a live stage view, timing diagram, event badges, and explanations, the tool makes abstract pipelining concepts easier to understand and demonstrate.

## 24. What To Emphasize in the Report

The report should emphasize:

- Educational purpose
- Integration with MARS Tools menu
- Cycle-by-cycle execution
- Timing diagram similar to lecture material
- Visual explanation of hazards
- Replay feature for classroom/demo use
- Clean final UI after removing the earlier embedded version

The report does not need to focus heavily on code. It should focus on what problem was solved, how the tool works, and how it helps students learn pipelining.

## 25. Final Project Summary

In short, this project adds a clean educational pipeline visualizer to MARS. It allows students to run MIPS programs in a five-stage pipelined mode and observe every cycle through diagrams and explanations. The tool is especially useful for understanding forwarding, stalls, and flushes, which are often difficult to learn from static lecture slides alone.

