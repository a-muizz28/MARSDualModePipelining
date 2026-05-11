# MARS Pipeline Visualizer Guide

This guide explains the basic idea of pipelining and how to use the MARS Pipeline Visualizer tool to understand what is happening cycle by cycle.

The goal of the tool is educational: it is not just meant to show that a program runs, but to help students see how multiple MIPS instructions overlap inside a five-stage pipeline, where hazards come from, and how stalls, flushes, and forwarding solve those hazards.

## 1. What Is Pipelining?

A normal single-cycle or simple sequential processor finishes one complete instruction before starting the next one. For example, it fetches an instruction, decodes it, executes it, accesses memory if needed, writes back the result, and only then moves to the next instruction.

Pipelining improves throughput by splitting instruction execution into stages. Instead of one instruction using the whole processor at once, different instructions use different parts of the processor in the same clock cycle.

An easy analogy is an assembly line. One item can be painted while another is being packaged and another is being inspected. Each item still takes several steps from start to finish, but many items are in progress at the same time.

In a five-stage MIPS pipeline, the stages are:

| Stage | Name | Purpose |
|---|---|---|
| IF | Instruction Fetch | Fetch the instruction from memory using the PC |
| ID | Instruction Decode | Decode instruction and read registers |
| EX | Execute | Perform ALU operation or calculate branch/memory address |
| MEM | Memory Access | Read/write data memory, or pass ALU result forward |
| WB | Write Back | Write the result into the register file |

## 2. How Instructions Overlap

Without pipelining, instructions behave like this:

```text
Instruction 1: IF ID EX MEM WB
Instruction 2:                IF ID EX MEM WB
Instruction 3:                               IF ID EX MEM WB
```

With pipelining, a new instruction can begin every cycle:

```text
Cycle:          1   2   3   4   5   6   7
Instruction 1: IF  ID  EX  MEM WB
Instruction 2:     IF  ID  EX  MEM WB
Instruction 3:         IF  ID  EX  MEM WB
```

Each individual instruction still takes five stages to complete, but after the pipeline fills, the processor can ideally complete one instruction per cycle.

This is why the visualizer focuses on a timing diagram: rows are instructions, columns are cycles, and each cell shows which stage that instruction is in during that cycle.

## 3. Pipeline Registers

Between stages, the processor stores intermediate information in pipeline registers. These are often written as:

| Pipeline Register | Between Stages |
|---|---|
| IF/ID | Between IF and ID |
| ID/EX | Between ID and EX |
| EX/MEM | Between EX and MEM |
| MEM/WB | Between MEM and WB |

The simulator internally tracks these pipeline registers. The UI displays the result as stage panes and timing cells, so students can see the instruction currently flowing through each stage.

## 4. Pipeline Hazards

Pipelining creates situations where the next instruction cannot safely continue. These situations are called hazards.

The visualizer focuses on the most important educational hazards:

## 4.1 Data Hazards

A data hazard happens when an instruction needs a value that a previous instruction has not written back yet.

Example:

```asm
addi $t0, $zero, 5
add  $t1, $t0, $t0
```

The `add` instruction needs `$t0`, but the `addi` instruction may not have reached WB yet. Without special handling, the `add` could read the old value of `$t0`.

## 4.2 Forwarding

Forwarding solves many data hazards by sending a result directly from a later pipeline stage to the EX stage, instead of waiting for WB.

The visualizer shows forwarding when an instruction in EX receives a value from:

| Forwarding Path | Meaning |
|---|---|
| EX/MEM -> EX | Result from the previous instruction is forwarded into EX |
| MEM/WB -> EX | Result from an older instruction is forwarded into EX |

In the timing diagram, forwarded execution is marked with `EX*`. The star means the instruction is in EX and used a forwarded operand.

## 4.3 Load-Use Hazard

A load-use hazard happens when an instruction immediately after a load needs the loaded value.

Example:

```asm
lw   $t1, 0($sp)
add  $t2, $t1, $t1
```

The load value is not available early enough for the very next instruction's EX stage. Even with forwarding, the pipeline usually needs one stall cycle.

The visualizer shows this as:

| UI Symbol | Meaning |
|---|---|
| `STALL` | A bubble was inserted because the pipeline had to wait |
| `ID/ST` | The instruction was held in/near decode while waiting |

The explanation panel will describe which register caused the hazard.

## 4.4 Control Hazards and Flushes

A control hazard happens when the processor fetches instructions after a branch or jump before it knows whether the branch is taken.

Example:

```asm
beq  $t0, $t1, target
addi $t2, $zero, 99
target:
addi $t2, $zero, 7
```

If the branch is taken, the already-fetched instruction after the branch may be wrong. The pipeline must remove those wrong-path instructions.

The visualizer shows this as:

| UI Symbol | Meaning |
|---|---|
| `FLUSH` | Wrong-path instruction was removed from the pipeline |

The event badge turns red and the explanation panel describes the branch/jump redirect.

## 5. Opening the Pipeline Visualizer

1. Open `Mars.jar`.
2. Open or write a MIPS program.
3. Assemble the program.
4. Open the tool from the MARS Tools menu:

```text
Tools -> Pipeline Visualizer
```

5. Click the tool's connect button.
6. Use:

```text
Run -> Step Cycle
```

or the shortcut shown by MARS for cycle stepping.

The tool automatically switches MARS into pipelined execution mode while connected. When disconnected, it restores the previous execution mode when safe.

## 6. Recommended Test Programs

The repository includes small programs designed to demonstrate common pipeline behavior:

| File | Demonstrates |
|---|---|
| `pipeline_tests/01_forwarding.asm` | ALU dependency and forwarding |
| `pipeline_tests/02_load_use_stall.asm` | Load-use hazard and stall |
| `pipeline_tests/03_branch_flush.asm` | Taken branch and flush |

Use these first when testing or demonstrating the visualizer.

## 7. UI Overview

The redesigned visualizer is organized like a classroom pipeline diagram.

Main areas:

1. Header and controls
2. Event badge
3. Live five-stage pipeline view
4. Stage detail panes
5. Pipeline timing diagram
6. Cycle explanation panel

## 8. Header and Controls

The header shows the current high-level state of the pipeline:

| Item | Meaning |
|---|---|
| Cycle | Current pipeline cycle number |
| Retired | Number of instructions completed so far |
| Next PC | Address of the next instruction fetch |

The control buttons are:

| Button | Purpose |
|---|---|
| Replay Cycle | Replays the most recent visual animation without executing again |
| Clear Timeline | Clears the visual timeline only |

Important: `Replay Cycle` is safe. It does not run the MIPS program again. It only restarts the latest animation and explanation so a student can rewatch what happened.

## 9. Event Badge

The event badge summarizes what kind of cycle just happened.

| Badge | Meaning |
|---|---|
| NORMAL | No hazard handling was needed |
| FORWARD | A value was forwarded into EX |
| STALL | A load-use stall was inserted |
| FLUSH | Wrong-path instructions were flushed |

This is useful during a demo because students can immediately see whether the current cycle is ordinary or special.

## 10. Live Five-Stage Pipeline View

The top visual area shows the five pipeline stages:

```text
IF -> ID -> EX -> MEM -> WB
```

Each stage box shows the instruction currently occupying that stage. If no instruction is present, the stage shows a bubble.

Color meanings:

| Color / Marking | Meaning |
|---|---|
| Blue IF | Instruction fetch |
| Green ID | Decode/register read |
| Orange EX | Execute/ALU |
| Purple MEM | Memory stage |
| Red WB | Write-back stage |
| Gray bubble | No useful instruction in that stage |
| Amber STALL | Pipeline waited for a hazard |
| Red FLUSH | Wrong-path instruction was removed |
| Dashed arc | Forwarding path |

Animated dots show movement between stages. Forwarding arcs show values being bypassed into EX.

## 11. Stage Detail Panes

Below the live stage boxes are five detail panes:

```text
IF | ID | EX | MEM | WB
```

Each pane shows:

| Field | Meaning |
|---|---|
| Instruction | Instruction currently associated with that stage |
| PC | Address of that instruction |
| Action text | Plain-English description of what the stage is doing |

Examples of action text:

```text
Fetch / IF-ID latch
Decode and read registers
Execute with forwarded operand
Memory access or pass ALU result
Write result back / retire
Bubble inserted by hazard detection
Flushed after taken branch/jump
```

These panes are intended to answer the question: "What is each pipeline stage doing right now?"

## 12. Pipeline Timing Diagram

The timing diagram is the main educational part of the interface.

Rows represent instructions.

Columns represent cycles.

Cells show which stage an instruction occupied in that cycle.

Example shape:

```text
Instruction              C1   C2   C3   C4   C5   C6
addi $t0,$zero,5         IF   ID   EX   MEM  WB
addi $t1,$zero,7              IF   ID   EX   MEM  WB
add $t2,$t0,$t1                    IF   ID   EX*  MEM
```

The timing diagram helps students see overlap. Instead of thinking of one instruction at a time, they can see several instructions moving together.

## 13. Timing Diagram Symbols

| Symbol | Meaning |
|---|---|
| IF | Instruction fetch |
| ID | Instruction decode |
| EX | Execute |
| EX* | Execute using forwarded data |
| MEM | Memory access |
| WB | Write back |
| STALL | Pipeline inserted a stall bubble |
| ID/ST | Instruction was held because of a stall |
| FLUSH | Wrong-path instruction was flushed |

The `EX*` marker is especially important. It tells students that the instruction did not wait for write-back; it received a value through forwarding.

## 14. Explanation Panel

The explanation panel gives a short sentence or two about the latest cycle.

Examples:

```text
Cycle 6: A load-use hazard was detected. The instruction in ID needs $t1 before the load can write it back, so IF/ID is held and one bubble is inserted into EX.
```

```text
Cycle 5: EX/MEM forwarded $t2 into EX. The dependent instruction can continue without waiting for normal write-back.
```

```text
Cycle 4: A branch or jump was taken in EX. The younger instructions already fetched behind it are flushed, and the next fetch PC is redirected to the target.
```

This panel is designed for students who are still learning how to interpret the timing diagram.

## 15. How to Study a Program With the Tool

Use the following process:

1. Assemble the program.
2. Open and connect the Pipeline Visualizer.
3. Press `Step Cycle`.
4. Look at the event badge.
5. Look at the live stage panes.
6. Find the newest column in the timing diagram.
7. Read the explanation panel.
8. Press `Replay Cycle` if the action was a stall, flush, or forwarding event.
9. Continue stepping cycle by cycle.

For learning, do not immediately press Run. Use cycle stepping so each pipeline transition is visible.

## 16. Understanding Forwarding With the Interface

When forwarding happens:

1. The event badge shows `FORWARD`.
2. The live view draws a dashed forwarding path.
3. The timing diagram marks the EX cell as `EX*`.
4. The explanation panel tells which forwarding path was used.

What to notice:

The consumer instruction reaches EX before the producer has fully written back. The processor avoids a stall by taking the value from a pipeline register instead of the register file.

## 17. Understanding Stalls With the Interface

When a load-use stall happens:

1. The event badge shows `STALL`.
2. The timing diagram shows `STALL` or `ID/ST`.
3. The IF stage may be marked as held.
4. The explanation panel names the register that caused the hazard.

What to notice:

The pipeline does not discard the dependent instruction. It holds it until the loaded value can be used safely.

## 18. Understanding Flushes With the Interface

When a branch or jump is taken:

1. The event badge shows `FLUSH`.
2. Younger wrong-path instructions are shown as flushed.
3. The timing diagram adds a `FLUSH` marker.
4. The explanation panel says that the next PC was redirected.

What to notice:

The pipeline may have already fetched instructions after the branch. If the branch changes control flow, those instructions must be removed.

## 19. What the Tool Is Best For

The visualizer is best for:

- Understanding five-stage MIPS pipeline flow
- Seeing how instructions overlap
- Watching forwarding solve ALU dependencies
- Watching load-use hazards create stalls
- Watching taken branches create flushes
- Demonstrating pipeline concepts in a classroom or viva/demo

## 20. Current Educational Scope

The pipelined simulator is intentionally focused on a useful teaching subset of MIPS instructions. It is designed for common integer arithmetic, loads/stores, branches/jumps, and simple syscall-based test programs.

If a program uses an unsupported instruction in pipelined mode, MARS may report that the instruction is unsupported for the pipeline simulator. That does not mean normal MARS is broken; it means the educational pipeline model does not yet implement that instruction.

## 21. Demo Script for Approval

A simple demonstration flow:

1. Open `pipeline_tests/01_forwarding.asm`.
2. Connect the Pipeline Visualizer.
3. Step until `FORWARD` appears.
4. Point out `EX*` in the timing diagram.
5. Press `Replay Cycle`.
6. Explain that the dependent instruction continued without a stall.
7. Open `pipeline_tests/02_load_use_stall.asm`.
8. Step until `STALL` appears.
9. Point out the held instruction and inserted bubble.
10. Open `pipeline_tests/03_branch_flush.asm`.
11. Step until `FLUSH` appears.
12. Explain that wrong-path instructions were removed.

This demonstrates the three key ideas most professors expect from a pipeline visualizer:

- Overlap
- Data hazard handling
- Control hazard handling

## 22. Quick Reference

| UI Part | What It Teaches |
|---|---|
| Live IF-ID-EX-MEM-WB boxes | What is currently in each stage |
| Stage detail panes | What each stage is doing |
| Timing diagram | How instructions move across cycles |
| Event badge | Whether the cycle is normal, forwarding, stall, or flush |
| Explanation panel | Why the pipeline behaved that way |
| Replay Cycle | Rewatch the latest visual event |

## 23. Summary

The Pipeline Visualizer turns the pipeline from an abstract lecture diagram into a step-by-step interactive view. Students can see instructions enter the pipeline, overlap with each other, encounter hazards, and recover through forwarding, stalls, or flushes.

The most important habit while using the tool is to connect the live stage view with the timing diagram:

- The live view answers: "What is happening right now?"
- The timing diagram answers: "How did we get here over time?"
- The explanation panel answers: "Why did the pipeline make this decision?"

