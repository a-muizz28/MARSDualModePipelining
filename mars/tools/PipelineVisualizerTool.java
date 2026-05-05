package mars.tools;

import mars.*;
import mars.mips.hardware.*;
import mars.ProgramStatement;
import mars.simulator.*;
import mars.util.Binary;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;

/**
 * MARS Tools-menu entry: animated 5-stage MIPS pipeline visualizer.
 *
 * <p>Works in <b>both</b> execution modes:
 * <ul>
 *   <li><b>Pipelined mode</b> – reads real pipeline latch state from
 *       {@link PipelineSimulator#getStageInfos()} each cycle and shows up to
 *       five instructions in-flight simultaneously with cycle-timing table.</li>
 *   <li><b>Normal (single-step) mode</b> – animates each instruction flowing
 *       sequentially through IF→ID→EX→MEM→WB, showing the conceptual
 *       pipeline stages even though execution is non-overlapping.</li>
 * </ul>
 *
 * <p>Both modes feature a 60 fps Swing-Timer driven animation:
 * a coloured dot flows along each arrow when data moves between latches,
 * and each newly active stage box pulses with a brightness flash.
 */
public class PipelineVisualizerTool extends AbstractMarsToolAndApplication {

   private static final String NAME    = "Pipeline Visualizer";
   private static final String HEADING = "5-Stage MIPS Pipeline — Animated Visualization";

   // ── Stage palette ─────────────────────────────────────────────────────────

   static final String[] STAGE_NAMES  = {"IF", "ID", "EX", "MEM", "WB"};
   static final Color[]  STAGE_COLORS = {
      new Color(41,  128, 185),   // IF  – blue
      new Color(39,  174,  96),   // ID  – green
      new Color(211,  84,   0),   // EX  – orange
      new Color(142,  68, 173),   // MEM – purple
      new Color(192,  57,  43),   // WB  – red
   };
   static final Color BUBBLE_BG = new Color(189, 195, 199);
   static final Color BUBBLE_FG = new Color( 99, 110, 114);

   // ── Tool fields ───────────────────────────────────────────────────────────

   private AnimatedPipelinePanel animPanel;
   private volatile int lastSeenCycle = -1;

   // ── MarsTool / AbstractMarsToolAndApplication ─────────────────────────────

   public PipelineVisualizerTool() {
      super(NAME, HEADING);
   }

   @Override
   public String getName() { return NAME; }

   @Override
   protected JComponent buildMainDisplayArea() {
      animPanel = new AnimatedPipelinePanel();
      return animPanel;
   }

   /** Observe text-segment reads – both modes fetch instructions from there. */
   @Override
   protected void addAsObserver() {
      addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress);
   }

   @Override
   protected void processMIPSUpdate(Observable resource, AccessNotice notice) {
      if (!notice.accessIsFromMIPS()) return;
      if (notice.getAccessType() != AccessNotice.READ) return;

      if (ExecutionController.isPipelinedMode()) {
         // ── Pipelined mode: advance once per pipeline cycle ───────────────
         int cycle = PipelineSimulator.getInstance().getCycleCount();
         if (cycle == lastSeenCycle) return;
         lastSeenCycle = cycle;
         SwingUtilities.invokeLater(() -> { if (animPanel != null) animPanel.advance(); });

      } else {
         // ── Normal mode: animate each instruction through stages ───────────
         MemoryAccessNotice man = (MemoryAccessNotice) notice;
         int addr = man.getAddress();
         String instr = fetchInstrText(addr);
         final int   fa = addr;
         final String fi = instr;
         SwingUtilities.invokeLater(() -> { if (animPanel != null) animPanel.advanceNormal(fa, fi); });
      }
   }

   @Override protected void updateDisplay() {}

   @Override
   protected void reset() {
      lastSeenCycle = -1;
      SwingUtilities.invokeLater(() -> { if (animPanel != null) animPanel.reset(); });
   }

   /** Resolve a text-segment address to a human-readable instruction mnemonic. */
   private String fetchInstrText(int addr) {
      try {
         ProgramStatement stmt = Globals.memory.getStatement(addr);
         if (stmt != null) {
            String t = stmt.getPrintableBasicAssemblyStatement();
            if (t != null && !t.isEmpty()) return t.trim();
         }
      } catch (Exception ignored) {}
      return Binary.intToHexString(addr);
   }

   // ═══════════════════════════════════════════════════════════════════════════
   // Animated pipeline panel
   // ═══════════════════════════════════════════════════════════════════════════

   private static class AnimatedPipelinePanel extends JPanel {

      // ── Pipelined-mode state ──────────────────────────────────────────────

      private PipelineSimulator.StageInfo[] currentStages = null;
      private PipelineSimulator.StageInfo   prevMemwb     = null;
      private int cycleCount   = 0;
      private int retiredCount = 0;

      // ── Normal-mode state ─────────────────────────────────────────────────

      /** Instruction currently animating through stages in normal mode. */
      private String  normalInstruction = null;
      private int     normalPC          = 0;
      private int     normalStepCount   = 0;
      /**
       * Fractional stage index 0.0–4.0.
       * floor(normalProgress) = stage currently being processed.
       * Advances at 0.04 per 16 ms tick ≈ 240 ms per stage ≈ 1.2 s full pipeline.
       */
      private float   normalProgress    = 0f;
      private boolean normalActive      = false;

      // ── Mode flag ─────────────────────────────────────────────────────────

      private boolean showingPipelined = false;

      // ── Shared animation state ────────────────────────────────────────────

      /** Progress 0→1 of the flowing dot on each inter-stage arrow (pipelined mode). */
      private final float[]   dotProgress = new float[4];
      private final boolean[] dotActive   = new boolean[4];

      /** Per-stage brightness-boost 1→0 (flash when new instruction arrives). */
      private final float[] stageFlash = new float[5];

      // ── Widgets ───────────────────────────────────────────────────────────

      private final JLabel            headerLabel;
      private final DrawPanel         drawPanel;
      private final DefaultTableModel tableModel;
      private final JTable            table;
      private final javax.swing.Timer animTimer;

      // ── Constructor ───────────────────────────────────────────────────────

      AnimatedPipelinePanel() {
         setLayout(new BorderLayout(0, 0));
         setBackground(new Color(236, 240, 241));
         setPreferredSize(new Dimension(760, 500));

         headerLabel = new JLabel(
            "  Connect the tool, assemble your program, then step to animate the pipeline.",
            JLabel.LEFT);
         headerLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
         headerLabel.setForeground(new Color(44, 62, 80));
         headerLabel.setBorder(BorderFactory.createEmptyBorder(5, 8, 3, 8));

         drawPanel = new DrawPanel();
         drawPanel.setPreferredSize(new Dimension(760, 148));
         drawPanel.setMinimumSize(new Dimension(400, 110));

         tableModel = new DefaultTableModel(
               new String[]{"#", "IF", "ID", "EX", "MEM", "WB"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
         };
         table = new JTable(tableModel);
         table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
         table.getTableHeader().setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
         table.setRowHeight(17);
         table.setGridColor(new Color(213, 216, 220));
         table.setShowGrid(true);
         table.setDefaultRenderer(Object.class, new TimingRenderer());
         table.getColumnModel().getColumn(0).setPreferredWidth(50);
         table.getColumnModel().getColumn(0).setMaxWidth(60);
         for (int i = 1; i <= 5; i++)
            table.getColumnModel().getColumn(i).setPreferredWidth(170);

         JScrollPane tableScroll = new JScrollPane(table,
               ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
               ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
         tableScroll.setBorder(
               BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(189, 195, 199)));

         JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, drawPanel, tableScroll);
         split.setResizeWeight(0.35);
         split.setDividerSize(5);
         split.setBorder(null);

         add(headerLabel, BorderLayout.NORTH);
         add(split,       BorderLayout.CENTER);

         animTimer = new javax.swing.Timer(16, e -> tick());
         animTimer.start();
      }

      // ── Public API ────────────────────────────────────────────────────────

      /** Pipelined mode: pull current latch state and trigger animation. */
      void advance() {
         PipelineSimulator sim = PipelineSimulator.getInstance();
         PipelineSimulator.StageInfo[] stages = sim.getStageInfos();
         if (stages == null) return;

         showingPipelined = true;
         cycleCount   = sim.getCycleCount();
         retiredCount = sim.getRetiredCount();

         // Flash stages that now hold a valid instruction
         for (int i = 0; i < 4; i++) {
            if (stages[i] != null && stages[i].valid) stageFlash[i] = 1.0f;
         }
         if (prevMemwb != null && prevMemwb.valid) stageFlash[4] = 1.0f;

         // Flowing dot on arrows whose destination is now valid
         for (int i = 0; i < 4; i++) {
            if (stages[i] != null && stages[i].valid) {
               dotProgress[i] = 0.0f;
               dotActive[i]   = true;
            }
         }

         currentStages = new PipelineSimulator.StageInfo[]{
            stages[0], stages[1], stages[2], stages[3], prevMemwb
         };

         String nextPC = stages[4].valid ? Binary.intToHexString(stages[4].pc) : "—";
         headerLabel.setText(String.format(
            "  [Pipelined]   Cycle: %d   │   Retired: %d   │   Next PC: %s",
            cycleCount, retiredCount, nextPC));

         tableModel.addRow(new Object[]{
            cycleCount,
            fmtCell(stages[0]), fmtCell(stages[1]),
            fmtCell(stages[2]), fmtCell(stages[3]),
            fmtCell(prevMemwb)
         });
         scrollToLastRow();
         prevMemwb = stages[3];
      }

      /**
       * Normal mode: start animating {@code instr} flowing through
       * IF → ID → EX → MEM → WB one stage at a time.
       */
      void advanceNormal(int pc, String instr) {
         showingPipelined  = false;
         normalPC          = pc;
         normalInstruction = instr;
         normalProgress    = 0f;
         normalActive      = true;
         normalStepCount++;

         stageFlash[0] = 1.0f; // flash IF immediately

         headerLabel.setText(String.format(
            "  [Normal]   Step: %d   │   PC: %s",
            normalStepCount, Binary.intToHexString(pc)));

         // Table: show the instruction in all 5 stage columns (conceptually runs through each)
         tableModel.addRow(new Object[]{
            normalStepCount, instr, instr, instr, instr, instr
         });
         scrollToLastRow();
      }

      /** Clear all state when the user clicks Reset. */
      void reset() {
         currentStages     = null;
         prevMemwb         = null;
         cycleCount        = 0;
         retiredCount      = 0;
         showingPipelined  = false;
         normalInstruction = null;
         normalActive      = false;
         normalProgress    = 0f;
         normalStepCount   = 0;
         Arrays.fill(dotActive,   false);
         Arrays.fill(dotProgress, 0f);
         Arrays.fill(stageFlash,  0f);
         tableModel.setRowCount(0);
         headerLabel.setText(
            "  Connect the tool, assemble your program, then step to animate the pipeline.");
         drawPanel.repaint();
      }

      // ── Animation tick ────────────────────────────────────────────────────

      private void tick() {
         boolean dirty = false;

         // Pipelined-mode dots
         for (int i = 0; i < 4; i++) {
            if (dotActive[i]) {
               dotProgress[i] = Math.min(1.0f, dotProgress[i] + 0.05f);
               if (dotProgress[i] >= 1.0f) dotActive[i] = false;
               dirty = true;
            }
         }

         // Normal-mode stage-walk: advance progress and flash each new stage
         if (normalActive && normalProgress < 4.0f) {
            float prev = normalProgress;
            normalProgress = Math.min(4.0f, normalProgress + 0.04f);
            int prevStage = (int) prev;
            int curStage  = (int) normalProgress;
            if (curStage != prevStage) stageFlash[curStage] = 1.0f;
            dirty = true;
         }

         // Decay stage flash
         for (int i = 0; i < 5; i++) {
            if (stageFlash[i] > 0f) {
               stageFlash[i] = Math.max(0f, stageFlash[i] - 0.035f);
               dirty = true;
            }
         }

         if (dirty) drawPanel.repaint();
      }

      // ── Helpers ───────────────────────────────────────────────────────────

      private void scrollToLastRow() {
         int last = tableModel.getRowCount() - 1;
         if (last >= 0) table.scrollRectToVisible(table.getCellRect(last, 0, true));
      }

      private static String fmtCell(PipelineSimulator.StageInfo s) {
         if (s == null || !s.valid) return "—";
         if (s.instruction != null && !s.instruction.isEmpty()) return s.instruction;
         return Binary.intToHexString(s.pc);
      }

      // ═══════════════════════════════════════════════════════════════════════
      // Inner: stage-box drawing panel
      // ═══════════════════════════════════════════════════════════════════════

      private class DrawPanel extends JPanel {

         DrawPanel() { setBackground(new Color(236, 240, 241)); }

         @Override
         protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
               g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                     RenderingHints.VALUE_ANTIALIAS_ON);
               g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                     RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
               g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                     RenderingHints.VALUE_RENDER_QUALITY);
               paint2D(g2);
            } finally {
               g2.dispose();
            }
         }

         private void paint2D(Graphics2D g2) {
            final int W = getWidth(), H = getHeight();
            final boolean hasSomething =
               (showingPipelined && currentStages != null) ||
               (!showingPipelined && normalInstruction != null);

            // ── Placeholder ───────────────────────────────────────────────
            if (!hasSomething) {
               g2.setColor(new Color(127, 140, 141));
               g2.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 12));
               String msg = "Connect the tool, assemble, then step to animate.";
               FontMetrics fm = g2.getFontMetrics();
               g2.drawString(msg,
                  Math.max(8, (W - fm.stringWidth(msg)) / 2),
                  H / 2 + fm.getAscent() / 2);
               return;
            }

            // ── Build per-stage display state ─────────────────────────────

            // For each stage: (valid, instruction text, pc)
            // We derive this differently per mode but render identically.
            final boolean[]  stageValid = new boolean[5];
            final String[]   stageInstr = new String[5];
            final int[]      stagePC    = new int[5];

            if (showingPipelined) {
               for (int i = 0; i < 5; i++) {
                  PipelineSimulator.StageInfo si = currentStages[i];
                  stageValid[i] = si != null && si.valid;
                  stageInstr[i] = si != null ? si.instruction : null;
                  stagePC[i]    = si != null ? si.pc : 0;
               }
            } else {
               // Normal mode: only the current stage holds the instruction
               int cur = Math.min(4, (int) normalProgress);
               for (int i = 0; i < 5; i++) {
                  stageValid[i] = normalActive && (i == cur);
                  stageInstr[i] = normalInstruction;
                  stagePC[i]    = normalPC;
               }
            }

            // ── Layout ────────────────────────────────────────────────────
            final int MARGIN  = 10;
            final int ARROW_W = 32;
            final int N       = 5;
            final int ARC     = 14;
            final int BOX_H   = Math.max(60, H - 34);
            final int BOX_W   = Math.max(60,
               (W - ARROW_W * (N - 1) - MARGIN * (N + 1)) / N);
            final int BOX_Y   = 14;

            int x = MARGIN;
            for (int i = 0; i < N; i++) {
               boolean active = stageValid[i];
               Color   base   = active ? STAGE_COLORS[i] : BUBBLE_BG;
               Color   textFg = active ? Color.WHITE      : BUBBLE_FG;

               // Flash blend toward white
               Color bg = base;
               float f  = stageFlash[i];
               if (f > 0f) {
                  int r  = Math.min(255, bg.getRed()   + (int) ((255-bg.getRed())   * f * 0.45f));
                  int gv = Math.min(255, bg.getGreen() + (int) ((255-bg.getGreen()) * f * 0.45f));
                  int bv = Math.min(255, bg.getBlue()  + (int) ((255-bg.getBlue())  * f * 0.45f));
                  bg = new Color(r, gv, bv);
               }

               // Drop shadow
               g2.setColor(new Color(0, 0, 0, 28));
               g2.fillRoundRect(x+3, BOX_Y+3, BOX_W, BOX_H, ARC, ARC);

               // Gradient fill
               g2.setPaint(new GradientPaint(
                  x, BOX_Y,         bg,
                  x, BOX_Y + BOX_H, bg.darker()));
               g2.fillRoundRect(x, BOX_Y, BOX_W, BOX_H, ARC, ARC);

               // Border
               g2.setColor(bg.darker().darker());
               g2.setStroke(new BasicStroke(1.3f));
               g2.drawRoundRect(x, BOX_Y, BOX_W, BOX_H, ARC, ARC);

               // Stage-name badge
               g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
               FontMetrics sfm = g2.getFontMetrics();
               g2.setColor(textFg);
               g2.drawString(STAGE_NAMES[i],
                  x + (BOX_W - sfm.stringWidth(STAGE_NAMES[i])) / 2, BOX_Y + 16);

               // Separator line below badge
               g2.setColor(active ? new Color(255,255,255,55) : new Color(0,0,0,18));
               g2.setStroke(new BasicStroke(0.8f));
               g2.drawLine(x+6, BOX_Y+21, x+BOX_W-6, BOX_Y+21);

               if (active) {
                  // Instruction mnemonic (middle)
                  g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
                  g2.setColor(Color.WHITE);
                  FontMetrics ifm = g2.getFontMetrics();
                  String instr = (stageInstr[i] != null && !stageInstr[i].isEmpty())
                        ? stageInstr[i] : Binary.intToHexString(stagePC[i]);
                  while (instr.length() > 3 && ifm.stringWidth(instr) > BOX_W - 8)
                     instr = instr.substring(0, instr.length() - 1);
                  g2.drawString(instr,
                     x + (BOX_W - ifm.stringWidth(instr)) / 2,
                     BOX_Y + BOX_H/2 + ifm.getAscent()/2 - 2);

                  // PC address (bottom)
                  g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 9));
                  g2.setColor(new Color(255, 255, 255, 190));
                  FontMetrics pfm = g2.getFontMetrics();
                  String pcStr = Binary.intToHexString(stagePC[i]);
                  g2.drawString(pcStr,
                     x + (BOX_W - pfm.stringWidth(pcStr)) / 2,
                     BOX_Y + BOX_H - 7);

               } else {
                  // Bubble label (middle)
                  g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
                  g2.setColor(BUBBLE_FG);
                  FontMetrics bfm = g2.getFontMetrics();
                  String bub = "bubble";
                  g2.drawString(bub,
                     x + (BOX_W - bfm.stringWidth(bub)) / 2,
                     BOX_Y + BOX_H/2 + bfm.getAscent()/2);
               }

               // ── Arrow + flowing dot ───────────────────────────────────
               if (i < N - 1) {
                  int ax    = x + BOX_W + 2;
                  int ay    = BOX_Y + BOX_H / 2;
                  int axEnd = ax + ARROW_W - 9;

                  // Arrow shaft
                  g2.setColor(new Color(90, 90, 90));
                  g2.setStroke(new BasicStroke(1.8f));
                  g2.drawLine(ax, ay, axEnd, ay);

                  // Arrowhead
                  int[] xp = {axEnd+7, axEnd, axEnd};
                  int[] yp = {ay,      ay-5,   ay+5};
                  g2.setColor(new Color(90, 90, 90));
                  g2.fillPolygon(xp, yp, 3);

                  // Flowing dot – source depends on mode
                  boolean showDot;
                  float   dotT;
                  if (showingPipelined) {
                     showDot = dotActive[i];
                     dotT    = dotProgress[i];
                  } else {
                     // Dot travels arrow i while normalProgress transitions from i → i+1
                     showDot = normalActive
                           && normalProgress > (float) i
                           && normalProgress < (float) (i + 1);
                     dotT = showDot ? (normalProgress - i) : 0f;
                  }

                  if (showDot) {
                     int   dx = ax + (int) ((axEnd - ax) * dotT);
                     Color dc = STAGE_COLORS[i];
                     g2.setColor(new Color(dc.getRed(), dc.getGreen(), dc.getBlue(), 215));
                     g2.fillOval(dx - 5, ay - 5, 10, 10);
                     g2.setColor(Color.WHITE);
                     g2.setStroke(new BasicStroke(1.2f));
                     g2.drawOval(dx - 5, ay - 5, 10, 10);
                  }
               }

               x += BOX_W + ARROW_W + MARGIN;
            }

            // ── Legend ────────────────────────────────────────────────────
            int legendY = BOX_Y + BOX_H + 7;
            if (legendY + 10 < H) {
               g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
               g2.setColor(new Color(127, 140, 141));
               g2.drawString(
                  "Coloured box = active instruction   "
                  + "Grey box = pipeline bubble / idle   "
                  + "● = data flowing between stages",
                  10, legendY + 8);
            }
         }
      }

      // ═══════════════════════════════════════════════════════════════════════
      // Inner: timing-table cell renderer
      // ═══════════════════════════════════════════════════════════════════════

      private static class TimingRenderer extends DefaultTableCellRenderer {

         @Override
         public Component getTableCellRendererComponent(
               JTable table, Object value, boolean sel, boolean foc, int row, int col) {
            super.getTableCellRendererComponent(table, value, sel, foc, row, col);
            setHorizontalAlignment(col == 0 ? CENTER : LEFT);
            if (!sel) {
               if (col == 0) {
                  setBackground(new Color(213, 216, 220));
                  setForeground(new Color(44,  62,  80));
                  setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
               } else {
                  String  txt = value != null ? value.toString() : "";
                  boolean bub = "—".equals(txt) || txt.isEmpty();
                  if (bub) {
                     setBackground(new Color(250, 250, 250));
                     setForeground(new Color(180, 180, 180));
                  } else {
                     setBackground(lighten(STAGE_COLORS[col - 1], 0.72f));
                     setForeground(new Color(33,  33,  33));
                  }
                  setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
               }
            }
            return this;
         }

         private static Color lighten(Color c, float frac) {
            return new Color(
               c.getRed()   + (int) ((255 - c.getRed())   * frac),
               c.getGreen() + (int) ((255 - c.getGreen()) * frac),
               c.getBlue()  + (int) ((255 - c.getBlue())  * frac));
         }
      }
   }
}
