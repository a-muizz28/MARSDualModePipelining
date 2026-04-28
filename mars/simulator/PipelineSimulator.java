package mars.simulator;

import java.util.Arrays;

import javax.swing.AbstractAction;

import mars.ErrorList;
import mars.ErrorMessage;
import mars.Globals;
import mars.MIPSprogram;
import mars.ProcessingException;
import mars.ProgramStatement;
import mars.mips.hardware.AddressErrorException;
import mars.mips.hardware.Coprocessor0;
import mars.mips.hardware.Memory;
import mars.mips.hardware.RegisterFile;
import mars.mips.instructions.BasicInstruction;
import mars.util.Binary;
import mars.util.SystemIO;
import mars.venus.RunGoAction;
import mars.venus.RunSpeedPanel;
import mars.venus.RunStepAction;
import mars.venus.RunStepCycleAction;

/*
Copyright (c) 2026

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

/**
 * Cycle-based five-stage pipeline simulator used when execution mode is
 * set to pipelined.
 *
 * v1 scope intentionally supports a focused educational subset and reports
 * unsupported instructions as reserved instruction exceptions.
 */
public class PipelineSimulator {
   private static PipelineSimulator simulator = null;

   private SimThread simulatorThread;
   private PipelineState state;
   private MIPSprogram currentProgram;

   private int lastCommittedAddress = -1;

   /** various reasons for simulate to end... */
   public static final int BREAKPOINT = Simulator.BREAKPOINT;
   public static final int EXCEPTION = Simulator.EXCEPTION;
   public static final int MAX_STEPS = Simulator.MAX_STEPS;
   public static final int NORMAL_TERMINATION = Simulator.NORMAL_TERMINATION;
   public static final int CLIFF_TERMINATION = Simulator.CLIFF_TERMINATION;
   public static final int PAUSE_OR_STOP = Simulator.PAUSE_OR_STOP;

   private PipelineSimulator() {
      simulatorThread = null;
      state = null;
      currentProgram = null;
   }

   public static synchronized PipelineSimulator getInstance() {
      if (simulator == null) {
         simulator = new PipelineSimulator();
      }
      return simulator;
   }

   public synchronized void resetState() {
      state = null;
      currentProgram = null;
      lastCommittedAddress = -1;
   }

   public synchronized int getLastCommittedAddress() {
      return lastCommittedAddress;
   }

   public synchronized boolean simulate(MIPSprogram p, int pc, int maxSteps, int[] breakPoints,
                                        AbstractAction actor, int stepMode) throws ProcessingException {
      simulatorThread = new SimThread(p, pc, maxSteps, breakPoints, actor, stepMode);
      simulatorThread.start();

      // Command-line mode: block until done, then propagate exceptions.
      if (actor == null) {
         simulatorThread.get();
         ProcessingException pe = simulatorThread.pe;
         boolean done = simulatorThread.done;
         if (done) {
            SystemIO.resetFiles();
         }
         simulatorThread = null;
         if (pe != null) {
            throw pe;
         }
         return done;
      }
      return true;
   }

   public synchronized void stopExecution(AbstractAction actor) {
      if (simulatorThread != null) {
         simulatorThread.setStop(actor);
         Simulator.getInstance().signalExternalStopListeners();
      }
   }

   private synchronized void ensurePipelineState(MIPSprogram program, int pc) {
      if (program != currentProgram || state == null) {
         currentProgram = program;
         state = new PipelineState();
         state.fetchPC = pc;
         state.fetchExhausted = false;
         state.cycles = 0;
         state.retiredInstructions = 0;
         RegisterFile.initializeProgramCounter(pc);
         return;
      }

      // If we are quiescent and PC changed externally (e.g. user edited register),
      // start a new pipeline stream at that PC.
      if (state.isQuiescent() && state.fetchPC != pc) {
         state = new PipelineState();
         state.fetchPC = pc;
         state.fetchExhausted = false;
         state.cycles = 0;
         state.retiredInstructions = 0;
         RegisterFile.initializeProgramCounter(pc);
      }
   }

   private boolean isBreakpoint(int[] breakPoints, int address) {
      return breakPoints != null && Arrays.binarySearch(breakPoints, address) >= 0;
   }

   private static int signExtend16(int value) {
      return (value << 16) >> 16;
   }

   private static int u16(int value) {
      return value & 0xFFFF;
   }

   private static int u8(int value) {
      return value & 0xFF;
   }

   private static int u32Compare(int lhs, int rhs) {
      long a = lhs & 0xFFFFFFFFL;
      long b = rhs & 0xFFFFFFFFL;
      if (a == b) {
         return 0;
      }
      return a < b ? -1 : 1;
   }

   private static String normalizeMnemonic(ProgramStatement statement) {
      if (statement == null || statement.getInstruction() == null ||
          statement.getInstruction().getName() == null) {
         return "";
      }
      return statement.getInstruction().getName().trim().toLowerCase();
   }

   private static int detectOverflowAdd(int a, int b, int sum) throws ArithmeticException {
      if ((a >= 0 && b >= 0 && sum < 0) || (a < 0 && b < 0 && sum >= 0)) {
         throw new ArithmeticException("arithmetic overflow");
      }
      return sum;
   }

   private static int detectOverflowSub(int a, int b, int diff) throws ArithmeticException {
      if ((a >= 0 && b < 0 && diff < 0) || (a < 0 && b >= 0 && diff >= 0)) {
         throw new ArithmeticException("arithmetic overflow");
      }
      return diff;
   }

   private int readMemory(int address, int widthBytes, boolean unsignedLoad) throws AddressErrorException {
      int raw;
      if (widthBytes == 4) {
         return Globals.memory.getWord(address);
      }
      if (widthBytes == 2) {
         raw = Globals.memory.getHalf(address) & 0xFFFF;
         return unsignedLoad ? raw : (raw << 16) >> 16;
      }
      raw = Globals.memory.getByte(address) & 0xFF;
      return unsignedLoad ? raw : (raw << 24) >> 24;
   }

   private void writeMemory(int address, int widthBytes, int value) throws AddressErrorException {
      if (widthBytes == 4) {
         Globals.memory.setWord(address, value);
      }
      else if (widthBytes == 2) {
         Globals.memory.setHalf(address, value);
      }
      else {
         Globals.memory.setByte(address, value);
      }
   }

   private ProcessingException unsupportedInstructionException(ProgramStatement statement) {
      return new ProcessingException(statement,
         "unsupported instruction in pipelined mode: " + statement.getPrintableBasicAssemblyStatement(),
         Exceptions.RESERVED_INSTRUCTION_EXCEPTION);
   }

   private ProcessingException invalidProgramCounterException(int pc, AddressErrorException e) {
      ErrorList el = new ErrorList();
      el.add(new ErrorMessage((MIPSprogram) null, 0, 0,
         "invalid program counter value: " + Binary.intToHexString(pc)));
      Coprocessor0.updateRegister(Coprocessor0.EPC, pc);
      return new ProcessingException(el, e);
   }

   private int applyForwardingA(IDEX idex, EXMEM exmem, MEMWB memwb, int value) {
      if (idex.readRs) {
         if (exmem.valid && exmem.regWrite && !exmem.memRead && exmem.writeReg != 0 && exmem.writeReg == idex.rs) {
            return exmem.forwardValue;
         }
         if (memwb.valid && memwb.regWrite && memwb.writeReg != 0 && memwb.writeReg == idex.rs) {
            return memwb.writeValue;
         }
      }
      return value;
   }

   private int applyForwardingB(IDEX idex, EXMEM exmem, MEMWB memwb, int value) {
      if (idex.readRt) {
         if (exmem.valid && exmem.regWrite && !exmem.memRead && exmem.writeReg != 0 && exmem.writeReg == idex.rt) {
            return exmem.forwardValue;
         }
         if (memwb.valid && memwb.regWrite && memwb.writeReg != 0 && memwb.writeReg == idex.rt) {
            return memwb.writeValue;
         }
      }
      return value;
   }

   private CycleResult advanceSingleCycle(int[] breakPoints, boolean breakpointsActive) throws ProcessingException {
      CycleResult result = new CycleResult();

      // Breakpoint check for WB-retiring instructions before architectural commit.
      if (breakpointsActive && state.memwb.valid && state.memwb.retireAtWb &&
          isBreakpoint(breakPoints, state.memwb.pc)) {
         result.breakpointHit = true;
         return result;
      }

      IFID nextIfid = new IFID();
      IDEX nextIdex = new IDEX();
      EXMEM nextExmem = new EXMEM();
      MEMWB nextMemwb = new MEMWB();

      int retiredThisCycle = 0;
      int committedAddressThisCycle = -1;

      // WB stage
      if (state.memwb.valid) {
        if (state.memwb.retireAtWb) {
            retiredThisCycle++;
            committedAddressThisCycle = state.memwb.pc;
            if (state.memwb.regWrite && state.memwb.writeReg != 0) {
               RegisterFile.updateRegister(state.memwb.writeReg, state.memwb.writeValue);
            }
         }
      }

      // MEM stage
      if (state.exmem.valid) {
         if (state.exmem.memWrite) {
            if (breakpointsActive && isBreakpoint(breakPoints, state.exmem.pc)) {
               result.breakpointHit = true;
               return result;
            }
            try {
               writeMemory(state.exmem.address, state.exmem.memWidthBytes, state.exmem.storeValue);
            }
            catch (AddressErrorException aee) {
               throw new ProcessingException(state.exmem.statement, aee);
            }
            retiredThisCycle++;
            committedAddressThisCycle = state.exmem.pc;
         }
         else if (state.exmem.memRead) {
            int loadValue;
            try {
               loadValue = readMemory(state.exmem.address, state.exmem.memWidthBytes, state.exmem.memUnsigned);
            }
            catch (AddressErrorException aee) {
               throw new ProcessingException(state.exmem.statement, aee);
            }
            nextMemwb.valid = true;
            nextMemwb.statement = state.exmem.statement;
            nextMemwb.pc = state.exmem.pc;
            nextMemwb.regWrite = state.exmem.regWrite;
            nextMemwb.writeReg = state.exmem.writeReg;
            nextMemwb.writeValue = loadValue;
            nextMemwb.retireAtWb = true;
         }
         else {
            nextMemwb.valid = true;
            nextMemwb.statement = state.exmem.statement;
            nextMemwb.pc = state.exmem.pc;
            nextMemwb.regWrite = state.exmem.regWrite;
            nextMemwb.writeReg = state.exmem.writeReg;
            nextMemwb.writeValue = state.exmem.forwardValue;
            nextMemwb.retireAtWb = state.exmem.retireAtWb;
         }
      }

      // EX stage
      boolean branchTaken = false;
      int branchTarget = 0;
      if (state.idex.valid) {
         int opA = applyForwardingA(state.idex, state.exmem, state.memwb, state.idex.rsValue);
         int opB = applyForwardingB(state.idex, state.exmem, state.memwb, state.idex.rtValue);
         int alu = 0;
         int storeValue = opB;

         nextExmem.valid = true;
         nextExmem.statement = state.idex.statement;
         nextExmem.pc = state.idex.pc;
         nextExmem.memRead = false;
         nextExmem.memWrite = false;
         nextExmem.memUnsigned = false;
         nextExmem.memWidthBytes = 4;
         nextExmem.regWrite = state.idex.regWrite;
         nextExmem.writeReg = state.idex.writeReg;
         nextExmem.retireAtWb = true;

         try {
            switch (state.idex.kind) {
               case DecodedInstruction.NOP:
                  alu = 0;
                  nextExmem.regWrite = false;
                  break;
               case DecodedInstruction.ADD:
                  alu = detectOverflowAdd(opA, opB, opA + opB);
                  break;
               case DecodedInstruction.ADDU:
                  alu = opA + opB;
                  break;
               case DecodedInstruction.SUB:
                  alu = detectOverflowSub(opA, opB, opA - opB);
                  break;
               case DecodedInstruction.SUBU:
                  alu = opA - opB;
                  break;
               case DecodedInstruction.AND:
                  alu = opA & opB;
                  break;
               case DecodedInstruction.OR:
                  alu = opA | opB;
                  break;
               case DecodedInstruction.XOR:
                  alu = opA ^ opB;
                  break;
               case DecodedInstruction.NOR:
                  alu = ~(opA | opB);
                  break;
               case DecodedInstruction.SLT:
                  alu = opA < opB ? 1 : 0;
                  break;
               case DecodedInstruction.SLTU:
                  alu = u32Compare(opA, opB) < 0 ? 1 : 0;
                  break;
               case DecodedInstruction.SLL:
                  alu = opB << state.idex.shamt;
                  break;
               case DecodedInstruction.SRL:
                  alu = opB >>> state.idex.shamt;
                  break;
               case DecodedInstruction.SRA:
                  alu = opB >> state.idex.shamt;
                  break;
               case DecodedInstruction.ADDI:
                  alu = detectOverflowAdd(opA, signExtend16(state.idex.imm), opA + signExtend16(state.idex.imm));
                  break;
               case DecodedInstruction.ADDIU:
                  alu = opA + signExtend16(state.idex.imm);
                  break;
               case DecodedInstruction.ANDI:
                  alu = opA & u16(state.idex.imm);
                  break;
               case DecodedInstruction.ORI:
                  alu = opA | u16(state.idex.imm);
                  break;
               case DecodedInstruction.XORI:
                  alu = opA ^ u16(state.idex.imm);
                  break;
               case DecodedInstruction.LUI:
                  alu = u16(state.idex.imm) << 16;
                  break;
               case DecodedInstruction.SLTI:
                  alu = opA < signExtend16(state.idex.imm) ? 1 : 0;
                  break;
               case DecodedInstruction.SLTIU:
                  alu = u32Compare(opA, signExtend16(state.idex.imm)) < 0 ? 1 : 0;
                  break;
               case DecodedInstruction.LW:
                  nextExmem.memRead = true;
                  nextExmem.address = opA + signExtend16(state.idex.imm);
                  nextExmem.memWidthBytes = 4;
                  break;
               case DecodedInstruction.LB:
                  nextExmem.memRead = true;
                  nextExmem.address = opA + signExtend16(state.idex.imm);
                  nextExmem.memWidthBytes = 1;
                  nextExmem.memUnsigned = false;
                  break;
               case DecodedInstruction.LBU:
                  nextExmem.memRead = true;
                  nextExmem.address = opA + signExtend16(state.idex.imm);
                  nextExmem.memWidthBytes = 1;
                  nextExmem.memUnsigned = true;
                  break;
               case DecodedInstruction.LH:
                  nextExmem.memRead = true;
                  nextExmem.address = opA + signExtend16(state.idex.imm);
                  nextExmem.memWidthBytes = 2;
                  nextExmem.memUnsigned = false;
                  break;
               case DecodedInstruction.LHU:
                  nextExmem.memRead = true;
                  nextExmem.address = opA + signExtend16(state.idex.imm);
                  nextExmem.memWidthBytes = 2;
                  nextExmem.memUnsigned = true;
                  break;
               case DecodedInstruction.SW:
                  nextExmem.memWrite = true;
                  nextExmem.address = opA + signExtend16(state.idex.imm);
                  nextExmem.memWidthBytes = 4;
                  nextExmem.storeValue = storeValue;
                  nextExmem.retireAtWb = false;
                  nextExmem.regWrite = false;
                  break;
               case DecodedInstruction.SH:
                  nextExmem.memWrite = true;
                  nextExmem.address = opA + signExtend16(state.idex.imm);
                  nextExmem.memWidthBytes = 2;
                  nextExmem.storeValue = storeValue;
                  nextExmem.retireAtWb = false;
                  nextExmem.regWrite = false;
                  break;
               case DecodedInstruction.SB:
                  nextExmem.memWrite = true;
                  nextExmem.address = opA + signExtend16(state.idex.imm);
                  nextExmem.memWidthBytes = 1;
                  nextExmem.storeValue = storeValue;
                  nextExmem.retireAtWb = false;
                  nextExmem.regWrite = false;
                  break;
               case DecodedInstruction.BEQ:
                  branchTaken = (opA == opB);
                  branchTarget = state.idex.pc + 4 + (signExtend16(state.idex.imm) << 2);
                  nextExmem.regWrite = false;
                  break;
               case DecodedInstruction.BNE:
                  branchTaken = (opA != opB);
                  branchTarget = state.idex.pc + 4 + (signExtend16(state.idex.imm) << 2);
                  nextExmem.regWrite = false;
                  break;
               case DecodedInstruction.J:
                  branchTaken = true;
                  branchTarget = ((state.idex.pc + 4) & 0xF0000000) | ((state.idex.target & 0x03FFFFFF) << 2);
                  nextExmem.regWrite = false;
                  break;
               case DecodedInstruction.JAL:
                  branchTaken = true;
                  branchTarget = ((state.idex.pc + 4) & 0xF0000000) | ((state.idex.target & 0x03FFFFFF) << 2);
                  alu = state.idex.pc + 4;
                  break;
               case DecodedInstruction.JR:
                  branchTaken = true;
                  branchTarget = opA;
                  nextExmem.regWrite = false;
                  break;
               case DecodedInstruction.SYSCALL:
                  ((BasicInstruction) state.idex.statement.getInstruction()).getSimulationCode()
                     .simulate(state.idex.statement);
                  nextExmem.regWrite = false;
                  break;
               default:
                  throw unsupportedInstructionException(state.idex.statement);
            }
         }
         catch (ArithmeticException ae) {
            throw new ProcessingException(state.idex.statement, ae.getMessage(), Exceptions.ARITHMETIC_OVERFLOW_EXCEPTION);
         }

         nextExmem.forwardValue = alu;
      }

      // ID stage + hazard detection
      boolean stall = false;
      if (state.ifid.valid) {
         DecodedInstruction decoded = decode(state.ifid.statement);
         if (!decoded.supported) {
            throw unsupportedInstructionException(state.ifid.statement);
         }
         int hazardReg = 0;
         if (state.idex.valid && state.idex.memRead && state.idex.writeReg != 0) {
            hazardReg = state.idex.writeReg;
         }
         if (hazardReg != 0 &&
             ((decoded.readRs && decoded.rs == hazardReg) || (decoded.readRt && decoded.rt == hazardReg))) {
            stall = true;
         }
         else {
            nextIdex.valid = true;
            nextIdex.statement = state.ifid.statement;
            nextIdex.pc = state.ifid.pc;
            nextIdex.kind = decoded.kind;
            nextIdex.rs = decoded.rs;
            nextIdex.rt = decoded.rt;
            nextIdex.shamt = decoded.shamt;
            nextIdex.imm = decoded.imm;
            nextIdex.target = decoded.target;
            nextIdex.readRs = decoded.readRs;
            nextIdex.readRt = decoded.readRt;
            nextIdex.regWrite = decoded.regWrite;
            nextIdex.memRead = decoded.memRead;
            nextIdex.writeReg = decoded.writeReg;
            nextIdex.rsValue = RegisterFile.getValue(decoded.rs);
            nextIdex.rtValue = RegisterFile.getValue(decoded.rt);
         }
      }

      // IF stage
      if (branchTaken) {
         nextIfid.clear();
         nextIdex.clear();
         state.fetchPC = branchTarget;
         state.fetchExhausted = false;
      }
      else if (stall) {
         nextIfid.copyFrom(state.ifid);
      }
      else {
         try {
            ProgramStatement fetched = Globals.memory.getStatement(state.fetchPC);
            if (fetched != null) {
               nextIfid.valid = true;
               nextIfid.pc = state.fetchPC;
               nextIfid.statement = fetched;
               state.fetchPC += 4;
               state.fetchExhausted = false;
            }
            else {
               nextIfid.clear();
               state.fetchExhausted = true;
            }
         }
         catch (AddressErrorException aee) {
            throw invalidProgramCounterException(state.fetchPC, aee);
         }
      }

      // Commit pipeline register updates.
      state.ifid = nextIfid;
      state.idex = nextIdex;
      state.exmem = nextExmem;
      state.memwb = nextMemwb;

      state.cycles++;
      state.retiredInstructions += retiredThisCycle;
      result.retiredThisCycle = retiredThisCycle;
      result.retiredAddress = committedAddressThisCycle;
      if (committedAddressThisCycle >= 0) {
         lastCommittedAddress = committedAddressThisCycle;
      }

      // Keep architectural PC visible as "next fetch" for the execute pane.
      RegisterFile.initializeProgramCounter(state.fetchPC);

      // If code stream has ended and no in-flight instructions remain, execution is done.
      if (state.fetchExhausted && state.isQuiescent()) {
         result.done = true;
         result.reason = CLIFF_TERMINATION;
      }
      return result;
   }

   private DecodedInstruction decode(ProgramStatement statement) {
      DecodedInstruction decoded = new DecodedInstruction();
      decoded.supported = true;
      decoded.statement = statement;

      // Some MARS encodings (notably syscall) may carry non-canonical binary
      // encodings while still being semantically valid instructions.
      String mnemonic = normalizeMnemonic(statement);
      if ("syscall".equals(mnemonic)) {
         decoded.kind = DecodedInstruction.SYSCALL;
         return decoded;
      }

      int binary = statement.getBinaryStatement();
      int opcode = (binary >>> 26) & 0x3F;
      int rs = (binary >>> 21) & 0x1F;
      int rt = (binary >>> 16) & 0x1F;
      int rd = (binary >>> 11) & 0x1F;
      int shamt = (binary >>> 6) & 0x1F;
      int funct = binary & 0x3F;
      int imm = binary & 0xFFFF;
      int target = binary & 0x03FFFFFF;

      decoded.rs = rs;
      decoded.rt = rt;
      decoded.shamt = shamt;
      decoded.imm = imm;
      decoded.target = target;

      switch (opcode) {
         case 0x00:
            switch (funct) {
               case 0x00:
                  decoded.kind = (binary == 0) ? DecodedInstruction.NOP : DecodedInstruction.SLL;
                  decoded.readRs = false;
                  decoded.readRt = (binary != 0);
                  decoded.regWrite = (binary != 0);
                  decoded.writeReg = rd;
                  break;
               case 0x02:
                  decoded.kind = DecodedInstruction.SRL;
                  decoded.readRt = true;
                  decoded.regWrite = true;
                  decoded.writeReg = rd;
                  break;
               case 0x03:
                  decoded.kind = DecodedInstruction.SRA;
                  decoded.readRt = true;
                  decoded.regWrite = true;
                  decoded.writeReg = rd;
                  break;
               case 0x08:
                  decoded.kind = DecodedInstruction.JR;
                  decoded.readRs = true;
                  break;
               case 0x0C:
                  decoded.kind = DecodedInstruction.SYSCALL;
                  break;
               case 0x20:
                  decoded.kind = DecodedInstruction.ADD;
                  decoded.readRs = true;
                  decoded.readRt = true;
                  decoded.regWrite = true;
                  decoded.writeReg = rd;
                  break;
               case 0x21:
                  decoded.kind = DecodedInstruction.ADDU;
                  decoded.readRs = true;
                  decoded.readRt = true;
                  decoded.regWrite = true;
                  decoded.writeReg = rd;
                  break;
               case 0x22:
                  decoded.kind = DecodedInstruction.SUB;
                  decoded.readRs = true;
                  decoded.readRt = true;
                  decoded.regWrite = true;
                  decoded.writeReg = rd;
                  break;
               case 0x23:
                  decoded.kind = DecodedInstruction.SUBU;
                  decoded.readRs = true;
                  decoded.readRt = true;
                  decoded.regWrite = true;
                  decoded.writeReg = rd;
                  break;
               case 0x24:
                  decoded.kind = DecodedInstruction.AND;
                  decoded.readRs = true;
                  decoded.readRt = true;
                  decoded.regWrite = true;
                  decoded.writeReg = rd;
                  break;
               case 0x25:
                  decoded.kind = DecodedInstruction.OR;
                  decoded.readRs = true;
                  decoded.readRt = true;
                  decoded.regWrite = true;
                  decoded.writeReg = rd;
                  break;
               case 0x26:
                  decoded.kind = DecodedInstruction.XOR;
                  decoded.readRs = true;
                  decoded.readRt = true;
                  decoded.regWrite = true;
                  decoded.writeReg = rd;
                  break;
               case 0x27:
                  decoded.kind = DecodedInstruction.NOR;
                  decoded.readRs = true;
                  decoded.readRt = true;
                  decoded.regWrite = true;
                  decoded.writeReg = rd;
                  break;
               case 0x2A:
                  decoded.kind = DecodedInstruction.SLT;
                  decoded.readRs = true;
                  decoded.readRt = true;
                  decoded.regWrite = true;
                  decoded.writeReg = rd;
                  break;
               case 0x2B:
                  decoded.kind = DecodedInstruction.SLTU;
                  decoded.readRs = true;
                  decoded.readRt = true;
                  decoded.regWrite = true;
                  decoded.writeReg = rd;
                  break;
               default:
                  decoded.supported = false;
                  break;
            }
            break;
         case 0x02:
            decoded.kind = DecodedInstruction.J;
            break;
         case 0x03:
            decoded.kind = DecodedInstruction.JAL;
            decoded.regWrite = true;
            decoded.writeReg = 31;
            break;
         case 0x04:
            decoded.kind = DecodedInstruction.BEQ;
            decoded.readRs = true;
            decoded.readRt = true;
            break;
         case 0x05:
            decoded.kind = DecodedInstruction.BNE;
            decoded.readRs = true;
            decoded.readRt = true;
            break;
         case 0x08:
            decoded.kind = DecodedInstruction.ADDI;
            decoded.readRs = true;
            decoded.regWrite = true;
            decoded.writeReg = rt;
            break;
         case 0x09:
            decoded.kind = DecodedInstruction.ADDIU;
            decoded.readRs = true;
            decoded.regWrite = true;
            decoded.writeReg = rt;
            break;
         case 0x0A:
            decoded.kind = DecodedInstruction.SLTI;
            decoded.readRs = true;
            decoded.regWrite = true;
            decoded.writeReg = rt;
            break;
         case 0x0B:
            decoded.kind = DecodedInstruction.SLTIU;
            decoded.readRs = true;
            decoded.regWrite = true;
            decoded.writeReg = rt;
            break;
         case 0x0C:
            decoded.kind = DecodedInstruction.ANDI;
            decoded.readRs = true;
            decoded.regWrite = true;
            decoded.writeReg = rt;
            break;
         case 0x0D:
            decoded.kind = DecodedInstruction.ORI;
            decoded.readRs = true;
            decoded.regWrite = true;
            decoded.writeReg = rt;
            break;
         case 0x0E:
            decoded.kind = DecodedInstruction.XORI;
            decoded.readRs = true;
            decoded.regWrite = true;
            decoded.writeReg = rt;
            break;
         case 0x0F:
            decoded.kind = DecodedInstruction.LUI;
            decoded.regWrite = true;
            decoded.writeReg = rt;
            break;
         case 0x20:
            decoded.kind = DecodedInstruction.LB;
            decoded.readRs = true;
            decoded.regWrite = true;
            decoded.memRead = true;
            decoded.writeReg = rt;
            break;
         case 0x21:
            decoded.kind = DecodedInstruction.LH;
            decoded.readRs = true;
            decoded.regWrite = true;
            decoded.memRead = true;
            decoded.writeReg = rt;
            break;
         case 0x23:
            decoded.kind = DecodedInstruction.LW;
            decoded.readRs = true;
            decoded.regWrite = true;
            decoded.memRead = true;
            decoded.writeReg = rt;
            break;
         case 0x24:
            decoded.kind = DecodedInstruction.LBU;
            decoded.readRs = true;
            decoded.regWrite = true;
            decoded.memRead = true;
            decoded.writeReg = rt;
            break;
         case 0x25:
            decoded.kind = DecodedInstruction.LHU;
            decoded.readRs = true;
            decoded.regWrite = true;
            decoded.memRead = true;
            decoded.writeReg = rt;
            break;
         case 0x28:
            decoded.kind = DecodedInstruction.SB;
            decoded.readRs = true;
            decoded.readRt = true;
            break;
         case 0x29:
            decoded.kind = DecodedInstruction.SH;
            decoded.readRs = true;
            decoded.readRt = true;
            break;
         case 0x2B:
            decoded.kind = DecodedInstruction.SW;
            decoded.readRs = true;
            decoded.readRt = true;
            break;
         default:
            decoded.supported = false;
            break;
      }

      return decoded;
   }

   /** Snapshot of one pipeline stage, consumed by the UI visualization panel. */
   public static final class StageInfo {
      public final boolean valid;
      public final int pc;
      public final String instruction; // human-readable mnemonic, or null for a bubble

      public StageInfo(boolean valid, int pc, String instruction) {
         this.valid = valid;
         this.pc = pc;
         this.instruction = instruction;
      }
   }

   /**
    * Returns structured stage data after the last cycle.
    * <ul>
    *   <li>result[0] – IF stage: instruction that was in IF this cycle (IF/ID latch)</li>
    *   <li>result[1] – ID stage: ID/EX latch</li>
    *   <li>result[2] – EX stage: EX/MEM latch</li>
    *   <li>result[3] – MEM stage: MEM/WB latch</li>
    *   <li>result[4] – next-fetch PC (for header display)</li>
    * </ul>
    * Returns {@code null} if the pipeline has not been initialized.
    */
   public synchronized StageInfo[] getStageInfos() {
      if (state == null) return null;
      StageInfo[] result = new StageInfo[5];
      result[0] = toStageInfo(state.ifid.valid,  state.ifid.pc,  state.ifid.statement);
      result[1] = toStageInfo(state.idex.valid,  state.idex.pc,  state.idex.statement);
      result[2] = toStageInfo(state.exmem.valid, state.exmem.pc, state.exmem.statement);
      result[3] = toStageInfo(state.memwb.valid, state.memwb.pc, state.memwb.statement);
      result[4] = state.fetchExhausted
            ? new StageInfo(false, 0, null)
            : new StageInfo(true, state.fetchPC, null);
      return result;
   }

   private StageInfo toStageInfo(boolean valid, int pc, ProgramStatement stmt) {
      if (!valid) return new StageInfo(false, 0, null);
      String text = stmt != null ? stmt.getPrintableBasicAssemblyStatement() : null;
      return new StageInfo(true, pc, text != null ? text.trim() : null);
   }

   public synchronized int getCycleCount() {
      return state != null ? state.cycles : 0;
   }

   public synchronized int getRetiredCount() {
      return state != null ? state.retiredInstructions : 0;
   }

   private String formatStageLine(boolean valid, int pc, ProgramStatement statement) {
      if (!valid || statement == null) {
         return "(bubble)";
      }
      String asm = statement.getPrintableBasicAssemblyStatement();
      if (asm == null) {
         asm = "";
      }
      return Binary.intToHexString(pc) + "  " + asm.trim();
   }

   public synchronized String getPipelineSnapshot() {
      if (state == null) {
         return "Pipeline not initialized.";
      }
      StringBuilder sb = new StringBuilder();
      sb.append("Cycle ").append(state.cycles)
        .append(" | Retired ").append(state.retiredInstructions)
        .append(" | NextPC ").append(Binary.intToHexString(state.fetchPC))
        .append('\n');
      sb.append("IF/ID : ").append(formatStageLine(state.ifid.valid, state.ifid.pc, state.ifid.statement)).append('\n');
      sb.append("ID/EX : ").append(formatStageLine(state.idex.valid, state.idex.pc, state.idex.statement)).append('\n');
      sb.append("EX/MEM: ").append(formatStageLine(state.exmem.valid, state.exmem.pc, state.exmem.statement)).append('\n');
      sb.append("MEM/WB: ").append(formatStageLine(state.memwb.valid, state.memwb.pc, state.memwb.statement));
      return sb.toString();
   }

   class SimThread extends SwingWorker {
      private MIPSprogram p;
      private int pc;
      private int maxSteps;
      private int[] breakPoints;
      private boolean done;
      private ProcessingException pe;
      private volatile boolean stop = false;
      private volatile AbstractAction stopper;
      private AbstractAction starter;
      private int constructReturnReason;
      private int stepMode;

      SimThread(MIPSprogram p, int pc, int maxSteps, int[] breakPoints,
                AbstractAction starter, int stepMode) {
         super(Globals.getGui() != null);
         this.p = p;
         this.pc = pc;
         this.maxSteps = maxSteps;
         this.breakPoints = breakPoints;
         this.done = false;
         this.pe = null;
         this.starter = starter;
         this.stopper = null;
         this.constructReturnReason = MAX_STEPS;
         this.stepMode = stepMode;
      }

      public void setStop(AbstractAction actor) {
         stop = true;
         stopper = actor;
      }

      public Object construct() {
         Thread.currentThread().setPriority(Thread.NORM_PRIORITY - 1);
         Thread.yield();

         if (breakPoints == null || breakPoints.length == 0) {
            breakPoints = null;
         }
         else {
            Arrays.sort(breakPoints);
         }

         ensurePipelineState(p, pc);
         if (p.getBackStepper() != null) {
            p.getBackStepper().setEnabled(false);
         }

         Simulator.getInstance().publishExecutionStart(maxSteps, pc);

         int retiredBefore = state.retiredInstructions;
         int cyclesBefore = state.cycles;
         boolean breakpointsActive = breakPoints != null;
         try {
            while (true) {
               if (stop) {
                  constructReturnReason = PAUSE_OR_STOP;
                  done = false;
                  Simulator.getInstance().publishExecutionStop(maxSteps, state.fetchPC);
                  return done;
               }

               CycleResult result = advanceSingleCycle(breakPoints, breakpointsActive);

               if (result.breakpointHit) {
                  constructReturnReason = BREAKPOINT;
                  done = false;
                  Simulator.getInstance().publishExecutionStop(maxSteps, state.fetchPC);
                  return done;
               }

               if (result.done) {
                  constructReturnReason = result.reason;
                  done = true;
                  SystemIO.resetFiles();
                  Simulator.getInstance().publishExecutionStop(maxSteps, state.fetchPC);
                  return done;
               }

               if (stepMode == ExecutionController.STEP_MODE_CYCLE) {
                  constructReturnReason = MAX_STEPS;
                  done = false;
                  Simulator.getInstance().publishExecutionStop(maxSteps, state.fetchPC);
                  return done;
               }

               if (state.retiredInstructions > retiredBefore && maxSteps == 1) {
                  constructReturnReason = MAX_STEPS;
                  done = false;
                  Simulator.getInstance().publishExecutionStop(maxSteps, state.fetchPC);
                  return done;
               }

               if (maxSteps > 0 && (state.retiredInstructions - retiredBefore) >= maxSteps) {
                  constructReturnReason = MAX_STEPS;
                  done = false;
                  Simulator.getInstance().publishExecutionStop(maxSteps, state.fetchPC);
                  return done;
               }

               // GUI update/speed control (same spirit as classic simulator).
               if (Globals.getGui() != null || Globals.runSpeedPanelExists) {
                  if (maxSteps != 1 &&
                      RunSpeedPanel.getInstance().getRunSpeed() < RunSpeedPanel.UNLIMITED_SPEED) {
                     try {
                        Thread.sleep((int) (1000 / RunSpeedPanel.getInstance().getRunSpeed()));
                     }
                     catch (InterruptedException ie) {
                        // ignore
                     }
                  }
               }
            }
         }
         catch (ProcessingException ex) {
            if (ex.errors() == null) {
               pe = null;
               constructReturnReason = NORMAL_TERMINATION;
            }
            else {
               pe = ex;
               constructReturnReason = EXCEPTION;
            }
            done = true;
            SystemIO.resetFiles();
            Simulator.getInstance().publishExecutionStop(maxSteps, state.fetchPC);
            return done;
         }
      }

      public void finished() {
         if (Globals.getGui() == null) {
            return;
         }
         String starterName = (String) starter.getValue(AbstractAction.NAME);
         if (starterName.equals("Step")) {
            ((RunStepAction) starter).stepped(done, constructReturnReason, pe);
         }
         else if (starterName.equals("Step Cycle")) {
            ((RunStepCycleAction) starter).stepped(done, constructReturnReason, pe);
         }
         else if (starterName.equals("Go")) {
            if (done) {
               ((RunGoAction) starter).stopped(pe, constructReturnReason);
            }
            else if (constructReturnReason == BREAKPOINT) {
               ((RunGoAction) starter).paused(done, constructReturnReason, pe);
            }
            else if (constructReturnReason == MAX_STEPS) {
               ((RunGoAction) starter).stopped(pe, constructReturnReason);
            }
            else {
               String stopperName = stopper == null ? "" :
                  (String) stopper.getValue(AbstractAction.NAME);
               if ("Pause".equals(stopperName)) {
                  ((RunGoAction) starter).paused(done, constructReturnReason, pe);
               }
               else if ("Stop".equals(stopperName)) {
                  ((RunGoAction) starter).stopped(pe, constructReturnReason);
               }
            }
         }
      }
   }

   private static final class CycleResult {
      private boolean done;
      private boolean breakpointHit;
      private int reason;
      private int retiredThisCycle;
      private int retiredAddress = -1;
   }

   private static final class PipelineState {
      private int fetchPC;
      private boolean fetchExhausted;
      private IFID ifid = new IFID();
      private IDEX idex = new IDEX();
      private EXMEM exmem = new EXMEM();
      private MEMWB memwb = new MEMWB();
      private int cycles;
      private int retiredInstructions;

      private boolean isQuiescent() {
         return !ifid.valid && !idex.valid && !exmem.valid && !memwb.valid;
      }
   }

   private static final class IFID {
      private boolean valid;
      private int pc;
      private ProgramStatement statement;

      private void clear() {
         valid = false;
         pc = 0;
         statement = null;
      }

      private void copyFrom(IFID other) {
         valid = other.valid;
         pc = other.pc;
         statement = other.statement;
      }
   }

   private static final class IDEX {
      private boolean valid;
      private ProgramStatement statement;
      private int pc;
      private int kind;
      private int rs;
      private int rt;
      private int shamt;
      private int imm;
      private int target;
      private boolean readRs;
      private boolean readRt;
      private boolean regWrite;
      private boolean memRead;
      private int writeReg;
      private int rsValue;
      private int rtValue;

      private void clear() {
         valid = false;
         statement = null;
      }
   }

   private static final class EXMEM {
      private boolean valid;
      private ProgramStatement statement;
      private int pc;
      private boolean memRead;
      private boolean memWrite;
      private boolean memUnsigned;
      private int memWidthBytes;
      private int address;
      private int storeValue;
      private boolean regWrite;
      private int writeReg;
      private int forwardValue;
      private boolean retireAtWb;
   }

   private static final class MEMWB {
      private boolean valid;
      private ProgramStatement statement;
      private int pc;
      private boolean regWrite;
      private int writeReg;
      private int writeValue;
      private boolean retireAtWb;
   }

   private static final class DecodedInstruction {
      private static final int NOP = 0;
      private static final int ADD = 1;
      private static final int ADDU = 2;
      private static final int SUB = 3;
      private static final int SUBU = 4;
      private static final int AND = 5;
      private static final int OR = 6;
      private static final int XOR = 7;
      private static final int NOR = 8;
      private static final int SLT = 9;
      private static final int SLTU = 10;
      private static final int SLL = 11;
      private static final int SRL = 12;
      private static final int SRA = 13;
      private static final int ADDI = 14;
      private static final int ADDIU = 15;
      private static final int ANDI = 16;
      private static final int ORI = 17;
      private static final int XORI = 18;
      private static final int LUI = 19;
      private static final int SLTI = 20;
      private static final int SLTIU = 21;
      private static final int LW = 22;
      private static final int SW = 23;
      private static final int LB = 24;
      private static final int LBU = 25;
      private static final int LH = 26;
      private static final int LHU = 27;
      private static final int SB = 28;
      private static final int SH = 29;
      private static final int BEQ = 30;
      private static final int BNE = 31;
      private static final int J = 32;
      private static final int JAL = 33;
      private static final int JR = 34;
      private static final int SYSCALL = 35;

      private ProgramStatement statement;
      private boolean supported;
      private int kind;
      private int rs;
      private int rt;
      private int shamt;
      private int imm;
      private int target;
      private boolean readRs;
      private boolean readRt;
      private boolean regWrite;
      private boolean memRead;
      private int writeReg;
   }
}
