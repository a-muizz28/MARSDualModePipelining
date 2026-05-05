package mars.venus;

import java.awt.event.ActionEvent;

import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.KeyStroke;

import mars.Globals;
import mars.ProcessingException;
import mars.mips.hardware.RegisterFile;
import mars.simulator.ExecutionController;
import mars.simulator.PipelineSimulator;
import mars.simulator.Simulator;

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
 * Action for cycle-by-cycle advancement in pipelined execution mode.
 */
public class RunStepCycleAction extends GuiAction {

   private String name;
   private ExecutePane executePane;

   public RunStepCycleAction(String name, Icon icon, String descrip,
         Integer mnemonic, KeyStroke accel, VenusUI gui) {
      super(name, icon, descrip, mnemonic, accel, gui);
   }

   public void actionPerformed(ActionEvent e) {
      name = this.getValue(Action.NAME).toString();
      executePane = mainUI.getMainPane().getExecutePane();

      if (!ExecutionController.isPipelinedMode()) {
         return;
      }
      if (!FileStatus.isAssembled()) {
         return;
      }
      if (!mainUI.getStarted()) {
         processProgramArgumentsIfAny();
      }
      mainUI.setStarted(true);
      // Auto-switch to the Pipeline visualization tab on first step
      mainUI.messagesPane.selectPipelineTab();
      executePane.getTextSegmentWindow().setCodeHighlighting(true);
      try {
         Globals.program.simulateCycleAtPC(this);
      }
      catch (ProcessingException pe) {
         // callback handles UI messaging
      }
   }

   public void stepped(boolean done, int reason, ProcessingException pe) {
      executePane.getRegistersWindow().updateRegisters();
      executePane.getCoprocessor1Window().updateRegisters();
      executePane.getCoprocessor0Window().updateRegisters();
      executePane.getDataSegmentWindow().updateValues();

      mainUI.messagesPane.getPipelineDiagramPanel().update();

      if (!done) {
         int address = PipelineSimulator.getInstance().getLastCommittedAddress();
         if (address >= 0) {
            executePane.getTextSegmentWindow().highlightStepAtAddress(address);
         }
         else {
            executePane.getTextSegmentWindow().highlightStepAtPC();
         }
         FileStatus.set(FileStatus.RUNNABLE);
      }
      if (done) {
         RunGoAction.resetMaxSteps();
         executePane.getTextSegmentWindow().unhighlightAllSteps();
         FileStatus.set(FileStatus.TERMINATED);
      }
      if (done && pe == null) {
         mainUI.getMessagesPane().postMarsMessage(
            "\n" + name + ": execution " +
            ((reason == Simulator.CLIFF_TERMINATION) ? "terminated due to null instruction."
               : "completed successfully.") + "\n\n");
         mainUI.getMessagesPane().postRunMessage(
            "\n-- program is finished running " +
            ((reason == Simulator.CLIFF_TERMINATION) ? "(dropped off bottom)" : "") + " --\n\n");
         mainUI.getMessagesPane().selectRunMessageTab();
      }
      if (pe != null) {
         RunGoAction.resetMaxSteps();
         mainUI.getMessagesPane().postMarsMessage(pe.errors().generateErrorReport());
         mainUI.getMessagesPane().postMarsMessage(
            "\n" + name + ": execution terminated with errors.\n\n");
         mainUI.getRegistersPane().setSelectedComponent(executePane.getCoprocessor0Window());
         FileStatus.set(FileStatus.TERMINATED);
         executePane.getTextSegmentWindow().setCodeHighlighting(true);
         executePane.getTextSegmentWindow().unhighlightAllSteps();
         executePane.getTextSegmentWindow().highlightStepAtAddress(RegisterFile.getProgramCounter() - 4);
      }
      mainUI.setReset(false);
   }

   private void processProgramArgumentsIfAny() {
      String programArguments = executePane.getTextSegmentWindow().getProgramArguments();
      if (programArguments == null || programArguments.length() == 0 ||
            !Globals.getSettings().getProgramArguments()) {
         return;
      }
      new mars.simulator.ProgramArgumentList(programArguments).storeProgramArguments();
   }
}
