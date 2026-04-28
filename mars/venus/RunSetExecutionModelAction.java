package mars.venus;

import java.awt.event.ActionEvent;

import javax.swing.Icon;
import javax.swing.KeyStroke;

import mars.Globals;
import mars.simulator.ExecutionController;

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
 * Switches between classic and pipelined execution engines.
 */
public class RunSetExecutionModelAction extends GuiAction {
   private final int model;

   public RunSetExecutionModelAction(String name, Icon icon, String descrip,
         Integer mnemonic, KeyStroke accel, VenusUI gui, int model) {
      super(name, icon, descrip, mnemonic, accel, gui);
      this.model = model;
   }

   public void actionPerformed(ActionEvent e) {
      if (ExecutionController.getExecutionModel() == model) {
         return;
      }
      ExecutionController.setExecutionModel(model);
      mainUI.setMenuState(FileStatus.get());
      if (Globals.getGui() != null) {
         String modelName = ExecutionController.isPipelinedMode() ? "Pipelined" : "Classic";
         mainUI.getMessagesPane().postMarsMessage("\nExecution model switched to " + modelName + ".\n\n");
      }
   }
}
