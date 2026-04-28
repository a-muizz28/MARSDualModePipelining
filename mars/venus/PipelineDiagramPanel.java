package mars.venus;

import mars.simulator.PipelineSimulator;
import mars.util.Binary;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;

/**
 * Graphical 5-stage MIPS pipeline visualization panel (educational).
 *
 * <p>The top section renders five coloured stage boxes (IF → ID → EX → MEM → WB)
 * with arrows between them and shows the instruction currently in each stage.
 * The bottom section is a scrollable timing-log table: one row per cycle,
 * one column per stage, so students can see how instructions flow through
 * the pipeline over time.
 *
 * <p>Call {@link #update()} after each pipeline cycle completes and
 * {@link #reset()} when the program is reset.
 */
public class PipelineDiagramPanel extends JPanel {

   // ── Stage metadata ──────────────────────────────────────────────────────

   private static final String[] STAGE_NAMES = {"IF", "ID", "EX", "MEM", "WB"};

   /** One distinct colour per stage (IF=blue, ID=green, EX=orange, MEM=purple, WB=red). */
   private static final Color[] STAGE_COLORS = {
      new Color(41,  128, 185),   // IF  – blue
      new Color(39,  174,  96),   // ID  – green
      new Color(211,  84,   0),   // EX  – orange
      new Color(142,  68, 173),   // MEM – purple
      new Color(192,  57,  43),   // WB  – red
   };

   private static final Color BUBBLE_BG  = new Color(189, 195, 199);
   private static final Color BUBBLE_FG  = new Color( 99, 110, 114);

   // ── State ───────────────────────────────────────────────────────────────

   /**
    * What to render in the five stage boxes.
    * Index 0=IF, 1=ID, 2=EX, 3=MEM, 4=WB.
    * The WB slot is tracked manually (see {@link #prevMemwb}).
    */
   private PipelineSimulator.StageInfo[] displayStages = null;
   private int   displayCycle   = 0;
   private int   displayRetired = 0;

   /**
    * Saved MEM/WB latch from the previous cycle snapshot.
    * In cycle N the WB stage is processing the instruction that was in
    * the MEM/WB latch at the START of cycle N – i.e. what result[3]
    * returned in the PREVIOUS call to {@link #update()}.
    */
   private PipelineSimulator.StageInfo prevMemwb = null;

   // ── Child components ────────────────────────────────────────────────────

   private final JLabel        headerLabel;
   private final StageDrawPanel stageDrawPanel;
   private final DefaultTableModel timingModel;
   private final JTable        timingTable;

   // ── Constructor ─────────────────────────────────────────────────────────

   public PipelineDiagramPanel() {
      setLayout(new BorderLayout(0, 0));
      setBackground(new Color(236, 240, 241));

      // -- Header --
      headerLabel = new JLabel(
         "  Switch to Pipelined mode, assemble, then use Step Cycle (F6) to see the visualization.",
         JLabel.LEFT);
      headerLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
      headerLabel.setForeground(new Color(44, 62, 80));
      headerLabel.setBorder(BorderFactory.createEmptyBorder(5, 8, 3, 8));
      add(headerLabel, BorderLayout.NORTH);

      // -- Stage box drawing panel --
      stageDrawPanel = new StageDrawPanel();
      stageDrawPanel.setMinimumSize(new Dimension(400, 100));
      stageDrawPanel.setPreferredSize(new Dimension(700, 125));

      // -- Timing log table --
      timingModel = new DefaultTableModel(
            new String[]{"Cycle", "IF", "ID", "EX", "MEM", "WB"}, 0) {
         @Override public boolean isCellEditable(int r, int c) { return false; }
      };
      timingTable = new JTable(timingModel);
      timingTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
      timingTable.getTableHeader().setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
      timingTable.setRowHeight(17);
      timingTable.setGridColor(new Color(213, 216, 220));
      timingTable.setShowGrid(true);
      timingTable.setDefaultRenderer(Object.class, new TimingRenderer());

      // Column widths
      timingTable.getColumnModel().getColumn(0).setPreferredWidth(50);
      timingTable.getColumnModel().getColumn(0).setMaxWidth(60);
      for (int i = 1; i <= 5; i++) {
         timingTable.getColumnModel().getColumn(i).setPreferredWidth(170);
      }

      JScrollPane tableScroll = new JScrollPane(timingTable,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
      tableScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(189, 195, 199)));

      // -- Split: stage boxes on top, timing log on bottom --
      JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, stageDrawPanel, tableScroll);
      split.setResizeWeight(0.50);
      split.setDividerSize(5);
      split.setBorder(null);
      add(split, BorderLayout.CENTER);
   }

   // ── Public API ──────────────────────────────────────────────────────────

   /** Refresh the diagram after a pipeline cycle has advanced. */
   public void update() {
      PipelineSimulator sim = PipelineSimulator.getInstance();
      PipelineSimulator.StageInfo[] stages = sim.getStageInfos();
      if (stages == null) return;

      displayCycle   = sim.getCycleCount();
      displayRetired = sim.getRetiredCount();

      // Build the 5 display slots:
      //   stages[0] = IF  (IF/ID latch – instruction that just completed IF)
      //   stages[1] = ID  (ID/EX latch)
      //   stages[2] = EX  (EX/MEM latch)
      //   stages[3] = MEM (MEM/WB latch)
      //   WB = what was in MEM/WB latch before this cycle (saved as prevMemwb)
      displayStages = new PipelineSimulator.StageInfo[]{
         stages[0], stages[1], stages[2], stages[3], prevMemwb
      };

      // Header text
      String nextPC = stages[4].valid ? Binary.intToHexString(stages[4].pc) : "—";
      headerLabel.setText(String.format(
         "  Cycle: %d   │   Retired: %d   │   Next PC: %s",
         displayCycle, displayRetired, nextPC));

      // Add row to timing log
      timingModel.addRow(new Object[]{
         displayCycle,
         fmtCell(stages[0]),
         fmtCell(stages[1]),
         fmtCell(stages[2]),
         fmtCell(stages[3]),
         fmtCell(prevMemwb)
      });
      // Auto-scroll to newest row
      int last = timingModel.getRowCount() - 1;
      timingTable.scrollRectToVisible(timingTable.getCellRect(last, 0, true));

      // Advance the saved WB stage for the next cycle
      prevMemwb = stages[3];

      stageDrawPanel.repaint();
   }

   /** Clear the diagram when the program is reset. */
   public void reset() {
      displayStages  = null;
      displayCycle   = 0;
      displayRetired = 0;
      prevMemwb      = null;
      timingModel.setRowCount(0);
      headerLabel.setText(
         "  Switch to Pipelined mode, assemble, then use Step Cycle (F6) to see the visualization.");
      stageDrawPanel.repaint();
   }

   // ── Helpers ─────────────────────────────────────────────────────────────

   private static String fmtCell(PipelineSimulator.StageInfo s) {
      if (s == null || !s.valid) return "—"; // em dash = bubble
      if (s.instruction != null && !s.instruction.isEmpty()) return s.instruction;
      return Binary.intToHexString(s.pc);
   }

   // ── Inner class: stage-box drawing panel ────────────────────────────────

   private class StageDrawPanel extends JPanel {

      StageDrawPanel() {
         setBackground(new Color(236, 240, 241));
      }

      @Override
      protected void paintComponent(Graphics g) {
         super.paintComponent(g);
         Graphics2D g2 = (Graphics2D) g.create();
         try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_QUALITY);
            paint2D(g2);
         } finally {
            g2.dispose();
         }
      }

      private void paint2D(Graphics2D g2) {
         int W = getWidth(), H = getHeight();

         if (displayStages == null) {
            // Placeholder message
            g2.setColor(new Color(127, 140, 141));
            g2.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 12));
            String msg = "Pipeline diagram appears here during step-by-step execution in Pipelined mode.";
            FontMetrics fm = g2.getFontMetrics();
            int tx = Math.max(8, (W - fm.stringWidth(msg)) / 2);
            g2.drawString(msg, tx, H / 2 + fm.getAscent() / 2);
            return;
         }

         final int MARGIN    = 10;
         final int ARROW_W   = 26;
         final int N         = 5;
         final int ARC       = 12;

         int totalArrows  = ARROW_W * (N - 1);
         int totalMargins = MARGIN  * (N + 1);
         int boxW = Math.max(60, (W - totalArrows - totalMargins) / N);
         int boxH = Math.max(60, H - 24);
         int boxY = 12;

         Font stageFont = new Font(Font.SANS_SERIF, Font.BOLD,  12);
         Font instrFont = new Font(Font.MONOSPACED, Font.PLAIN, 10);
         Font pcFont    = new Font(Font.MONOSPACED, Font.PLAIN,  9);

         int x = MARGIN;
         for (int i = 0; i < N; i++) {
            PipelineSimulator.StageInfo info = displayStages[i];
            boolean active = info != null && info.valid;
            Color   bg     = active ? STAGE_COLORS[i] : BUBBLE_BG;
            Color   fg     = active ? Color.WHITE      : BUBBLE_FG;

            // Drop shadow
            g2.setColor(new Color(0, 0, 0, 28));
            g2.fillRoundRect(x + 3, boxY + 3, boxW, boxH, ARC, ARC);

            // Box gradient fill
            g2.setPaint(new GradientPaint(
               x, boxY,        bg,
               x, boxY + boxH, bg.darker()));
            g2.fillRoundRect(x, boxY, boxW, boxH, ARC, ARC);

            // Box border
            g2.setColor(bg.darker().darker());
            g2.setStroke(new BasicStroke(1.3f));
            g2.drawRoundRect(x, boxY, boxW, boxH, ARC, ARC);

            // Stage label (top band)
            g2.setFont(stageFont);
            g2.setColor(fg);
            FontMetrics sfm = g2.getFontMetrics();
            String sname = STAGE_NAMES[i];
            g2.drawString(sname, x + (boxW - sfm.stringWidth(sname)) / 2, boxY + 16);

            // Thin separator below stage label
            g2.setColor(active ? new Color(255, 255, 255, 55) : new Color(0, 0, 0, 18));
            g2.setStroke(new BasicStroke(0.8f));
            g2.drawLine(x + 6, boxY + 21, x + boxW - 6, boxY + 21);

            // Instruction text (middle) and PC (bottom)
            if (active) {
               // Instruction mnemonic
               g2.setFont(instrFont);
               g2.setColor(Color.WHITE);
               FontMetrics ifm = g2.getFontMetrics();
               String instr = (info.instruction != null && !info.instruction.isEmpty())
                     ? info.instruction
                     : Binary.intToHexString(info.pc);
               // Truncate to fit
               while (instr.length() > 3 && ifm.stringWidth(instr) > boxW - 8) {
                  instr = instr.substring(0, instr.length() - 1);
               }
               g2.drawString(instr,
                  x + (boxW - ifm.stringWidth(instr)) / 2,
                  boxY + boxH / 2 + ifm.getAscent() / 2 - 2);

               // PC address at bottom
               g2.setFont(pcFont);
               g2.setColor(new Color(255, 255, 255, 190));
               FontMetrics pfm = g2.getFontMetrics();
               String pcStr = Binary.intToHexString(info.pc);
               g2.drawString(pcStr,
                  x + (boxW - pfm.stringWidth(pcStr)) / 2,
                  boxY + boxH - 7);
            } else {
               // Bubble label
               g2.setFont(instrFont);
               g2.setColor(BUBBLE_FG);
               FontMetrics bfm = g2.getFontMetrics();
               String bub = "bubble";
               g2.drawString(bub,
                  x + (boxW - bfm.stringWidth(bub)) / 2,
                  boxY + boxH / 2 + bfm.getAscent() / 2);
            }

            // Arrow to next stage
            if (i < N - 1) {
               int ax = x + boxW + 2;
               int ay = boxY + boxH / 2;
               g2.setColor(new Color(90, 90, 90));
               g2.setStroke(new BasicStroke(1.8f));
               g2.drawLine(ax, ay, ax + ARROW_W - 8, ay);
               // Arrowhead
               int[] xp = {ax + ARROW_W - 5, ax + ARROW_W - 12, ax + ARROW_W - 12};
               int[] yp = {ay,               ay - 5,             ay + 5};
               g2.fillPolygon(xp, yp, 3);
            }

            x += boxW + ARROW_W + MARGIN;
         }

         // Legend strip at bottom
         int legendY = boxY + boxH + 6;
         if (legendY + 10 < H) {
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
            g2.setColor(new Color(127, 140, 141));
            g2.drawString("Coloured box = active instruction   Grey box = pipeline bubble", 10, legendY + 8);
         }
      }
   }

   // ── Inner class: timing-log cell renderer ───────────────────────────────

   private static class TimingRenderer extends DefaultTableCellRenderer {

      @Override
      public Component getTableCellRendererComponent(
            JTable table, Object value, boolean selected, boolean focused, int row, int col) {
         super.getTableCellRendererComponent(table, value, selected, focused, row, col);
         setHorizontalAlignment(col == 0 ? CENTER : LEFT);
         if (!selected) {
            if (col == 0) {
               setBackground(new Color(213, 216, 220));
               setForeground(new Color(44, 62, 80));
               setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
            } else {
               String text = value != null ? value.toString() : "";
               boolean bubble = "—".equals(text) || text.isEmpty();
               if (bubble) {
                  setBackground(new Color(250, 250, 250));
                  setForeground(new Color(180, 180, 180));
               } else {
                  setBackground(lighten(STAGE_COLORS[col - 1], 0.72f));
                  setForeground(new Color(33, 33, 33));
               }
               setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            }
         }
         return this;
      }

      /** Blend a colour toward white by {@code fraction} (0 = original, 1 = white). */
      private static Color lighten(Color c, float fraction) {
         int r = c.getRed()   + (int) ((255 - c.getRed())   * fraction);
         int g = c.getGreen() + (int) ((255 - c.getGreen()) * fraction);
         int b = c.getBlue()  + (int) ((255 - c.getBlue())  * fraction);
         return new Color(r, g, b);
      }
   }
}
