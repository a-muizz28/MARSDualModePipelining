package mars.simulator;

import javax.swing.AbstractAction;

import mars.Globals;
import mars.MIPSprogram;
import mars.ProcessingException;

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
 * Runtime execution model selector and dispatcher.
 * Classic mode uses the legacy instruction-at-a-time simulator.
 * Pipelined mode uses cycle-based simulation.
 */
public final class ExecutionController {

   public static final int EXECUTION_MODEL_CLASSIC = 0;
   public static final int EXECUTION_MODEL_PIPELINED = 1;

   public static final int STEP_MODE_INSTRUCTION = 0;
   public static final int STEP_MODE_CYCLE = 1;

   private static int executionModel = EXECUTION_MODEL_CLASSIC;

   private ExecutionController() { }

   public static int getExecutionModel() {
      return executionModel;
   }

   public static boolean isClassicMode() {
      return executionModel == EXECUTION_MODEL_CLASSIC;
   }

   public static boolean isPipelinedMode() {
      return executionModel == EXECUTION_MODEL_PIPELINED;
   }

   public static void setExecutionModel(int model) {
      if (model != EXECUTION_MODEL_CLASSIC && model != EXECUTION_MODEL_PIPELINED) {
         throw new IllegalArgumentException("Invalid execution model");
      }
      if (executionModel != model) {
         executionModel = model;
         if (Globals.program != null && Globals.program.getBackStepper() != null) {
            Globals.program.getBackStepper().setEnabled(backsteppingSupported());
         }
         PipelineSimulator.getInstance().resetState();
      }
   }

   public static boolean backsteppingSupported() {
      return executionModel == EXECUTION_MODEL_CLASSIC;
   }

   public static boolean cycleSteppingSupported() {
      return executionModel == EXECUTION_MODEL_PIPELINED;
   }

   public static boolean simulate(MIPSprogram p, int pc, int maxSteps, int[] breakPoints,
                                  AbstractAction actor, int stepMode) throws ProcessingException {
      if (executionModel == EXECUTION_MODEL_PIPELINED) {
         return PipelineSimulator.getInstance().simulate(p, pc, maxSteps, breakPoints, actor, stepMode);
      }
      return Simulator.getInstance().simulate(p, pc, maxSteps, breakPoints, actor);
   }

   public static void stopExecution(AbstractAction actor) {
      if (executionModel == EXECUTION_MODEL_PIPELINED) {
         PipelineSimulator.getInstance().stopExecution(actor);
      }
      else {
         Simulator.getInstance().stopExecution(actor);
      }
   }
}
