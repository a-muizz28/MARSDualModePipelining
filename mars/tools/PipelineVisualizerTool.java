package mars.tools;

import mars.*;
import mars.mips.hardware.*;
import mars.simulator.*;
import mars.util.Binary;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Observable;

/**
 * MARS Tools-menu entry: animated 5-stage MIPS pipeline visualizer with full
 * hazard, stall, flush, and forwarding visualization.
 *
 * <h3>Pipelined mode features</h3>
 * <ul>
 *   <li>Five stage boxes (IF → WB) updated each cycle with instruction mnemonics.</li>
 *   <li><b>Stall bubbles</b> shown in amber with "STALL" label; the stalled IF
 *       stage gets a gold "HELD" badge.</li>
 *   <li><b>Flush bubbles</b> shown in red with "FLUSH" label after branches/jumps.</li>
 *   <li><b>Forwarding paths</b> drawn as animated dashed bezier arcs below the
 *       stage boxes: orange for EX/MEM→EX, purple for MEM/WB→EX.</li>
 *   <li>Hazard info bar below the diagram describes the current event in plain
 *       English with the involved register name.</li>
 *   <li>Cycle-timing table with per-row colour coding for stall (amber) and
 *       flush (red) rows.</li>
 * </ul>
 *
 * <h3>Normal (non-pipelined) mode</h3>
 * Each step animates the instruction flowing sequentially IF → ID → EX → MEM → WB.
 */
public class PipelineVisualizerTool extends AbstractMarsToolAndApplication {

   private static final String NAME    = "Pipeline Visualizer";
   private static final String HEADING = "5-Stage MIPS Pipeline — Hazard & Forwarding Visualization";

   // ── Stage palette ─────────────────────────────────────────────────────────

   static final String[] STAGE_NAMES  = {"IF", "ID", "EX", "MEM", "WB"};
   static final Color[] STAGE_COLORS = {
      new Color(41,  128, 185),   // IF  – blue
      new Color(39,  174,  96),   // ID  – green
      new Color(211,  84,   0),   // EX  – orange
      new Color(142,  68, 173),   // MEM – purple
      new Color(192,  57,  43),   // WB  – red
   };
   static final Color BUBBLE_BG   = new Color(189, 195, 199);
   static final Color BUBBLE_FG   = new Color( 99, 110, 114);
   static final Color STALL_COLOR = new Color(230, 126,  34);  // amber
   static final Color FLUSH_COLOR = new Color(192,  57,  43);  // crimson

   // ── Tool fields ───────────────────────────────────────────────────────────

   private AnimatedPipelinePanel animPanel;
   private volatile int lastSeenCycle = -1;

   // ── AbstractMarsToolAndApplication ───────────────────────────────────────

   public PipelineVisualizerTool() {
      super(NAME, HEADING);
   }

   @Override public String getName() { return NAME; }

   @Override
   protected JComponent buildMainDisplayArea() {
      animPanel = new AnimatedPipelinePanel();
      return animPanel;
   }

   @Override
   protected void addAsObserver() {
      addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress);
   }

   @Override
   protected void processMIPSUpdate(Observable resource, AccessNotice notice) {
      if (!notice.accessIsFromMIPS()) return;
      if (notice.getAccessType() != AccessNotice.READ) return;

      if (ExecutionController.isPipelinedMode()) {
         int cycle = PipelineSimulator.getInstance().getCycleCount();
         if (cycle == lastSeenCycle) return;
         lastSeenCycle = cycle;
         SwingUtilities.invokeLater(() -> { if (animPanel != null) animPanel.advance(); });
      } else {
         MemoryAccessNotice man = (MemoryAccessNotice) notice;
         int    addr  = man.getAddress();
         String instr = fetchInstrText(addr);
         final int fa = addr; final String fi = instr;
         SwingUtilities.invokeLater(() -> { if (animPanel != null) animPanel.advanceNormal(fa, fi); });
      }
   }

   @Override protected void updateDisplay() {}

   @Override
   protected void reset() {
      lastSeenCycle = -1;
      SwingUtilities.invokeLater(() -> { if (animPanel != null) animPanel.reset(); });
   }

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

   private static String regName(int reg) {
      if (reg < 0) return "?";
      try { return RegisterFile.getRegisters()[reg].getName(); }
      catch (Exception e) { return "$" + reg; }
   }

   // ═══════════════════════════════════════════════════════════════════════════
   // Animated pipeline panel
   // ═══════════════════════════════════════════════════════════════════════════

   private static class AnimatedPipelinePanel extends JPanel {

      // ── Row-event constants (for timing table row colouring) ──────────────
      private static final int EV_NORMAL  = 0;
      private static final int EV_STALL   = 1;
      private static final int EV_FLUSH   = 2;
      private static final int EV_FORWARD = 3;

      // ── Pipelined mode state ──────────────────────────────────────────────
      private PipelineSimulator.StageInfo[]  currentStages  = null;
      private PipelineSimulator.StageInfo    prevMemwb      = null;
      private PipelineSimulator.CycleEvent   lastCycleEvent = PipelineSimulator.CycleEvent.NONE;
      private int cycleCount = 0, retiredCount = 0;
      private boolean showingPipelined = false;

      // ── Normal-mode state ─────────────────────────────────────────────────
      private String  normalInstruction = null;
      private int     normalPC = 0, normalStepCount = 0;
      private float   normalProgress = 0f;  // 0.0–4.0: current stage
      private boolean normalActive   = false;

      // ── Forwarding animation (pipelined mode) ─────────────────────────────
      private float   fwdExExProgress  = 0f;
      private boolean fwdExExActive    = false;
      private float   fwdMemExProgress = 0f;
      private boolean fwdMemExActive   = false;

      // ── Stage-box animation ───────────────────────────────────────────────
      private final float[]   dotProgress = new float[4];
      private final boolean[] dotActive   = new boolean[4];
      private final float[]   stageFlash  = new float[5];

      // ── Geometry (set during paint, used for arc drawing in same call) ────
      private final int[] stageX   = new int[5];
      private int paintedBoxW, paintedBoxH, paintedBoxY;

      // ── Widgets ───────────────────────────────────────────────────────────
      private final JLabel            headerLabel;
      private final JLabel            hazardInfoBar;
      private final DrawPanel         drawPanel;
      private final DefaultTableModel tableModel;
      private final JTable            table;
      private final List<Integer>     rowEvents = new ArrayList<>();
      private final javax.swing.Timer animTimer;

      // ── Constructor ───────────────────────────────────────────────────────
      AnimatedPipelinePanel() {
         setLayout(new BorderLayout(0, 0));
         setBackground(new Color(236, 240, 241));
         setPreferredSize(new Dimension(780, 540));

         // Header
         headerLabel = new JLabel(
            "  Connect, assemble, then step to animate the pipeline.", JLabel.LEFT);
         headerLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
         headerLabel.setForeground(new Color(44, 62, 80));
         headerLabel.setBorder(BorderFactory.createEmptyBorder(5, 8, 2, 8));

         // Hazard info bar (between diagram and table)
         hazardInfoBar = new JLabel(" ", JLabel.LEFT);
         hazardInfoBar.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
         hazardInfoBar.setOpaque(true);
         hazardInfoBar.setBackground(new Color(236, 240, 241));
         hazardInfoBar.setForeground(new Color(127, 140, 141));
         hazardInfoBar.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));

         // Draw panel (stage boxes + arcs)
         drawPanel = new DrawPanel();
         drawPanel.setPreferredSize(new Dimension(780, 195));
         drawPanel.setMinimumSize(new Dimension(400, 140));

         // Wrap draw panel + hazard bar
         JPanel topPanel = new JPanel(new BorderLayout(0, 0));
         topPanel.setBackground(new Color(236, 240, 241));
         topPanel.add(drawPanel, BorderLayout.CENTER);
         topPanel.add(hazardInfoBar, BorderLayout.SOUTH);

         // Timing table
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

         JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topPanel, tableScroll);
         split.setResizeWeight(0.42);
         split.setDividerSize(5);
         split.setBorder(null);

         add(headerLabel, BorderLayout.NORTH);
         add(split,       BorderLayout.CENTER);

         animTimer = new javax.swing.Timer(16, e -> tick());
         animTimer.start();
      }

      // ── Public API ────────────────────────────────────────────────────────

      void advance() {
         PipelineSimulator sim = PipelineSimulator.getInstance();
         PipelineSimulator.StageInfo[]  stages = sim.getStageInfos();
         PipelineSimulator.CycleEvent   evt    = sim.getLastCycleEvent();
         if (stages == null) return;

         showingPipelined = true;
         cycleCount   = sim.getCycleCount();
         retiredCount = sim.getRetiredCount();
         lastCycleEvent = (evt != null) ? evt : PipelineSimulator.CycleEvent.NONE;

         // Flash valid stages
         for (int i = 0; i < 4; i++)
            if (stages[i] != null && stages[i].valid) stageFlash[i] = 1.0f;
         if (prevMemwb != null && prevMemwb.valid) stageFlash[4] = 1.0f;

         // Flowing inter-stage dots
         for (int i = 0; i < 4; i++) {
            if (stages[i] != null && stages[i].valid) {
               dotProgress[i] = 0.0f;
               dotActive[i]   = true;
            }
         }

         // Forwarding arc animations
         if (lastCycleEvent.forwardingType == PipelineSimulator.CycleEvent.FWD_EX_TO_EX
               || lastCycleEvent.forwardingType == PipelineSimulator.CycleEvent.FWD_BOTH) {
            fwdExExProgress = 0.0f;
            fwdExExActive   = true;
         }
         if (lastCycleEvent.forwardingType == PipelineSimulator.CycleEvent.FWD_MEM_TO_EX
               || lastCycleEvent.forwardingType == PipelineSimulator.CycleEvent.FWD_BOTH) {
            fwdMemExProgress = 0.0f;
            fwdMemExActive   = true;
         }

         // Snapshot for rendering (WB = what was in MEM/WB last cycle)
         currentStages = new PipelineSimulator.StageInfo[]{
            stages[0], stages[1], stages[2], stages[3], prevMemwb
         };

         // Header
         String nextPC = stages[4].valid ? Binary.intToHexString(stages[4].pc) : "—";
         headerLabel.setText(String.format(
            "  [Pipelined]   Cycle: %d   │   Retired: %d   │   Next PC: %s",
            cycleCount, retiredCount, nextPC));

         // Hazard info bar
         updateHazardBar(lastCycleEvent);

         // Timing table row
         int eventType = lastCycleEvent.stallOccurred ? EV_STALL
                       : lastCycleEvent.branchFlush   ? EV_FLUSH
                       : (lastCycleEvent.forwardingType != PipelineSimulator.CycleEvent.FWD_NONE) ? EV_FORWARD
                       : EV_NORMAL;
         rowEvents.add(eventType);
         tableModel.addRow(new Object[]{
            cycleCount,
            fmtCell(stages[0]), fmtCell(stages[1]),
            fmtCell(stages[2]), fmtCell(stages[3]),
            fmtCell(prevMemwb)
         });
         scrollToLastRow();
         prevMemwb = stages[3];
      }

      void advanceNormal(int pc, String instr) {
         showingPipelined  = false;
         lastCycleEvent    = PipelineSimulator.CycleEvent.NONE;
         normalPC          = pc;
         normalInstruction = instr;
         normalProgress    = 0f;
         normalActive      = true;
         normalStepCount++;
         stageFlash[0]     = 1.0f;

         headerLabel.setText(String.format(
            "  [Normal]   Step: %d   │   PC: %s",
            normalStepCount, Binary.intToHexString(pc)));
         hazardInfoBar.setBackground(new Color(236, 240, 241));
         hazardInfoBar.setForeground(new Color(127, 140, 141));
         hazardInfoBar.setText("  Sequential execution — instruction flows through all stages in order.");

         rowEvents.add(EV_NORMAL);
         tableModel.addRow(new Object[]{normalStepCount, instr, instr, instr, instr, instr});
         scrollToLastRow();
      }

      void reset() {
         currentStages     = null;
         prevMemwb         = null;
         lastCycleEvent    = PipelineSimulator.CycleEvent.NONE;
         showingPipelined  = false;
         normalInstruction = null;
         normalActive      = false;
         normalProgress    = 0f;
         normalStepCount   = 0;
         cycleCount        = 0;
         retiredCount      = 0;
         fwdExExActive     = false;
         fwdMemExActive    = false;
         Arrays.fill(dotActive,   false);
         Arrays.fill(dotProgress, 0f);
         Arrays.fill(stageFlash,  0f);
         rowEvents.clear();
         tableModel.setRowCount(0);
         headerLabel.setText("  Connect, assemble, then step to animate the pipeline.");
         hazardInfoBar.setBackground(new Color(236, 240, 241));
         hazardInfoBar.setForeground(new Color(127, 140, 141));
         hazardInfoBar.setText(" ");
         drawPanel.repaint();
      }

      // ── Animation tick ────────────────────────────────────────────────────

      private void tick() {
         boolean dirty = false;

         // Inter-stage flowing dots (pipelined mode)
         for (int i = 0; i < 4; i++) {
            if (dotActive[i]) {
               dotProgress[i] = Math.min(1.0f, dotProgress[i] + 0.05f);
               if (dotProgress[i] >= 1.0f) dotActive[i] = false;
               dirty = true;
            }
         }

         // Forwarding arc dots
         if (fwdExExActive) {
            fwdExExProgress = Math.min(1.0f, fwdExExProgress + 0.04f);
            if (fwdExExProgress >= 1.0f) fwdExExActive = false;
            dirty = true;
         }
         if (fwdMemExActive) {
            fwdMemExProgress = Math.min(1.0f, fwdMemExProgress + 0.03f);
            if (fwdMemExProgress >= 1.0f) fwdMemExActive = false;
            dirty = true;
         }

         // Normal-mode stage walk
         if (normalActive && normalProgress < 4.0f) {
            float prev = normalProgress;
            normalProgress = Math.min(4.0f, normalProgress + 0.04f);
            if ((int) normalProgress != (int) prev) stageFlash[(int) normalProgress] = 1.0f;
            dirty = true;
         }

         // Stage flash decay
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

      private void updateHazardBar(PipelineSimulator.CycleEvent evt) {
         if (evt.stallOccurred) {
            hazardInfoBar.setBackground(new Color(255, 243, 205));
            hazardInfoBar.setForeground(new Color(130,  80,   0));
            String rn = evt.stallReg > 0 ? regName(evt.stallReg) : "?";
            hazardInfoBar.setText(
               "  ⚠  Load-use hazard — 1 stall cycle inserted  " +
               "(lw result in $" + rn + " needed by the following instruction)");
         } else if (evt.branchFlush) {
            hazardInfoBar.setBackground(new Color(255, 220, 220));
            hazardInfoBar.setForeground(new Color(150,   0,   0));
            hazardInfoBar.setText(
               "  ↩  Branch / jump taken — 2 instructions flushed from pipeline");
         } else if (evt.forwardingType != PipelineSimulator.CycleEvent.FWD_NONE) {
            hazardInfoBar.setBackground(new Color(220, 240, 255));
            hazardInfoBar.setForeground(new Color(  0,  60, 140));
            String msg;
            if (evt.forwardingType == PipelineSimulator.CycleEvent.FWD_BOTH) {
               msg = "EX/MEM → EX ($" + regName(evt.fwdExExReg)  + ")  and  " +
                     "MEM/WB → EX ($" + regName(evt.fwdMemExReg) + ")";
            } else if (evt.forwardingType == PipelineSimulator.CycleEvent.FWD_EX_TO_EX) {
               msg = "EX/MEM → EX  ($" + regName(evt.fwdExExReg)  + " bypassed — no stall needed)";
            } else {
               msg = "MEM/WB → EX  ($" + regName(evt.fwdMemExReg) + " bypassed — no stall needed)";
            }
            hazardInfoBar.setText("  →  Forwarding: " + msg);
         } else {
            hazardInfoBar.setBackground(new Color(236, 240, 241));
            hazardInfoBar.setForeground(new Color(127, 140, 141));
            hazardInfoBar.setText("  No hazards — normal pipeline operation");
         }
      }

      // ═══════════════════════════════════════════════════════════════════════
      // Inner: draw panel
      // ═══════════════════════════════════════════════════════════════════════

      private class DrawPanel extends JPanel {

         DrawPanel() { setBackground(new Color(236, 240, 241)); }

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
            final int W = getWidth(), H = getHeight();
            final boolean hasPipelined = showingPipelined && currentStages != null;
            final boolean hasNormal    = !showingPipelined && normalInstruction != null;

            // ── Placeholder ───────────────────────────────────────────────
            if (!hasPipelined && !hasNormal) {
               g2.setColor(new Color(127, 140, 141));
               g2.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 12));
               String msg = "Connect the tool, assemble, then step to animate.";
               FontMetrics fm = g2.getFontMetrics();
               g2.drawString(msg, Math.max(8, (W - fm.stringWidth(msg)) / 2), H / 2 + fm.getAscent() / 2);
               return;
            }

            // ── Layout ────────────────────────────────────────────────────
            final int MARGIN  = 10;
            final int ARROW_W = 32;
            final int N       = 5;
            final int ARC     = 14;
            // Reserve ~70 px below stage boxes: 50 for forwarding arcs + 20 for legend
            paintedBoxH = Math.max(60, H - 70);
            paintedBoxW = Math.max(60, (W - ARROW_W * (N - 1) - MARGIN * (N + 1)) / N);
            paintedBoxY = 14;

            // ── Build per-stage display (works for both modes) ────────────
            final boolean[] stageValid = new boolean[5];
            final String[]  stageInstr = new String[5];
            final int[]     stagePCArr = new int[5];
            final Color[]   stageBubbleColor = new Color[5];
            final String[]  stageBubbleLabel = new String[5];

            if (hasPipelined) {
               for (int i = 0; i < 5; i++) {
                  PipelineSimulator.StageInfo si = currentStages[i];
                  stageValid[i] = si != null && si.valid;
                  stageInstr[i] = si != null ? si.instruction : null;
                  stagePCArr[i] = si != null ? si.pc : 0;
               }
               // Determine bubble type per stage
               for (int i = 0; i < 5; i++) {
                  if (!stageValid[i]) {
                     Color bc = BUBBLE_BG; String bl = "bubble";
                     if (lastCycleEvent.stallOccurred  && i == 1) { bc = STALL_COLOR; bl = "STALL"; }
                     if (lastCycleEvent.branchFlush    && (i == 0 || i == 1)) { bc = FLUSH_COLOR; bl = "FLUSH"; }
                     stageBubbleColor[i] = bc;
                     stageBubbleLabel[i] = bl;
                  }
               }
            } else {
               // Normal mode: only current stage is active
               int cur = Math.min(4, (int) normalProgress);
               for (int i = 0; i < 5; i++) {
                  stageValid[i] = normalActive && (i == cur);
                  stageInstr[i] = normalInstruction;
                  stagePCArr[i] = normalPC;
                  stageBubbleColor[i] = BUBBLE_BG;
                  stageBubbleLabel[i] = "—";
               }
            }

            // ── Draw stage boxes ──────────────────────────────────────────
            int x = MARGIN;
            for (int i = 0; i < N; i++) {
               stageX[i] = x;
               boolean active = stageValid[i];
               Color   base   = active ? STAGE_COLORS[i] : stageBubbleColor[i];
               Color   textFg = active ? Color.WHITE      :
                                (base == STALL_COLOR || base == FLUSH_COLOR) ? Color.WHITE : BUBBLE_FG;

               // Flash blend
               Color bg = base;
               float f  = stageFlash[i];
               if (f > 0f && active) {
                  int r  = Math.min(255, bg.getRed()   + (int) ((255 - bg.getRed())   * f * 0.45f));
                  int gv = Math.min(255, bg.getGreen() + (int) ((255 - bg.getGreen()) * f * 0.45f));
                  int bv = Math.min(255, bg.getBlue()  + (int) ((255 - bg.getBlue())  * f * 0.45f));
                  bg = new Color(r, gv, bv);
               }

               // Shadow
               g2.setColor(new Color(0, 0, 0, 28));
               g2.fillRoundRect(x + 3, paintedBoxY + 3, paintedBoxW, paintedBoxH, ARC, ARC);
               // Fill
               g2.setPaint(new GradientPaint(x, paintedBoxY, bg, x, paintedBoxY + paintedBoxH, bg.darker()));
               g2.fillRoundRect(x, paintedBoxY, paintedBoxW, paintedBoxH, ARC, ARC);
               // Border
               g2.setColor(bg.darker().darker());
               g2.setStroke(new BasicStroke(1.3f));
               g2.drawRoundRect(x, paintedBoxY, paintedBoxW, paintedBoxH, ARC, ARC);

               // Stage name
               g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
               FontMetrics sfm = g2.getFontMetrics();
               g2.setColor(textFg);
               g2.drawString(STAGE_NAMES[i],
                  x + (paintedBoxW - sfm.stringWidth(STAGE_NAMES[i])) / 2, paintedBoxY + 16);
               // Divider
               g2.setColor(active ? new Color(255,255,255,55) : new Color(0,0,0,18));
               g2.setStroke(new BasicStroke(0.8f));
               g2.drawLine(x + 6, paintedBoxY + 21, x + paintedBoxW - 6, paintedBoxY + 21);

               if (active) {
                  // Instruction mnemonic
                  g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
                  g2.setColor(Color.WHITE);
                  FontMetrics ifm = g2.getFontMetrics();
                  String instr = (stageInstr[i] != null && !stageInstr[i].isEmpty())
                        ? stageInstr[i] : Binary.intToHexString(stagePCArr[i]);
                  while (instr.length() > 3 && ifm.stringWidth(instr) > paintedBoxW - 8)
                     instr = instr.substring(0, instr.length() - 1);
                  g2.drawString(instr,
                     x + (paintedBoxW - ifm.stringWidth(instr)) / 2,
                     paintedBoxY + paintedBoxH / 2 + ifm.getAscent() / 2 - 2);
                  // PC
                  g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 9));
                  g2.setColor(new Color(255, 255, 255, 190));
                  FontMetrics pfm = g2.getFontMetrics();
                  String pcStr = Binary.intToHexString(stagePCArr[i]);
                  g2.drawString(pcStr,
                     x + (paintedBoxW - pfm.stringWidth(pcStr)) / 2,
                     paintedBoxY + paintedBoxH - 7);

                  // ── "HELD" badge for stalled IF stage ─────────────────
                  if (hasPipelined && lastCycleEvent.stallOccurred && i == 0) {
                     g2.setColor(STALL_COLOR);
                     g2.fillRoundRect(x + paintedBoxW - 36, paintedBoxY + 3, 32, 14, 4, 4);
                     g2.setColor(Color.WHITE);
                     g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
                     g2.drawString("HELD", x + paintedBoxW - 32, paintedBoxY + 12);
                  }

               } else {
                  // Bubble label (STALL / FLUSH / bubble / —)
                  g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
                  g2.setColor(textFg);
                  FontMetrics bfm = g2.getFontMetrics();
                  String lbl = stageBubbleLabel[i] != null ? stageBubbleLabel[i] : "bubble";
                  g2.drawString(lbl,
                     x + (paintedBoxW - bfm.stringWidth(lbl)) / 2,
                     paintedBoxY + paintedBoxH / 2 + bfm.getAscent() / 2);
               }

               // ── Arrow + flowing dot ───────────────────────────────────
               if (i < N - 1) {
                  int ax    = x + paintedBoxW + 2;
                  int ay    = paintedBoxY + paintedBoxH / 2;
                  int axEnd = ax + ARROW_W - 9;

                  g2.setColor(new Color(90, 90, 90));
                  g2.setStroke(new BasicStroke(1.8f));
                  g2.drawLine(ax, ay, axEnd, ay);
                  int[] xp = {axEnd + 7, axEnd, axEnd};
                  int[] yp = {ay,        ay - 5, ay + 5};
                  g2.fillPolygon(xp, yp, 3);

                  boolean showDot;
                  float   dotT;
                  if (hasPipelined) {
                     showDot = dotActive[i];
                     dotT    = dotProgress[i];
                  } else {
                     showDot = normalActive && normalProgress > i && normalProgress < i + 1;
                     dotT    = showDot ? (normalProgress - i) : 0f;
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

               x += paintedBoxW + ARROW_W + MARGIN;
            }

            // ── Forwarding arcs (pipelined mode only) ─────────────────────
            if (hasPipelined && lastCycleEvent.forwardingType != PipelineSimulator.CycleEvent.FWD_NONE) {
               int fwdType = lastCycleEvent.forwardingType;
               int arcBaseY = paintedBoxY + paintedBoxH;

               if (fwdType == PipelineSimulator.CycleEvent.FWD_EX_TO_EX ||
                   fwdType == PipelineSimulator.CycleEvent.FWD_BOTH) {
                  drawForwardingArc(g2,
                     stageX[2], stageX[1], arcBaseY, paintedBoxW,
                     STAGE_COLORS[2],
                     "$" + regName(lastCycleEvent.fwdExExReg) + "  EX→EX",
                     fwdExExProgress, fwdExExActive, 28);
               }
               if (fwdType == PipelineSimulator.CycleEvent.FWD_MEM_TO_EX ||
                   fwdType == PipelineSimulator.CycleEvent.FWD_BOTH) {
                  drawForwardingArc(g2,
                     stageX[3], stageX[1], arcBaseY, paintedBoxW,
                     STAGE_COLORS[3],
                     "$" + regName(lastCycleEvent.fwdMemExReg) + "  MEM→EX",
                     fwdMemExProgress, fwdMemExActive, 44);
               }
            }

            // ── Legend (below forwarding arcs – deepest arc uses ~44 px) ─────
            int legendY = paintedBoxY + paintedBoxH + 56;
            if (legendY + 10 < H) {
               g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
               g2.setColor(new Color(127, 140, 141));
               g2.drawString(
                  "Coloured = active   Grey = bubble   Amber = STALL   Red = FLUSH   Dashed arc = forwarding path",
                  10, legendY + 8);
            }
         }

         // ── Forwarding arc helper ─────────────────────────────────────────

         private void drawForwardingArc(Graphics2D g2,
               int srcX, int dstX, int topY, int bw,
               Color color, String label,
               float dotT, boolean dotActive, int depth) {

            float p0x = srcX + bw / 2f, p0y = topY + 3;
            float p2x = dstX + bw / 2f, p2y = topY + 3;
            float p1x = (p0x + p2x) / 2f, p1y = topY + depth;

            // Dashed arc line
            GeneralPath path = new GeneralPath();
            path.moveTo(p0x, p0y);
            path.quadTo(p1x, p1y, p2x, p2y);
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 170));
            g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
               1.0f, new float[]{5.0f, 3.0f}, 0.0f));
            g2.draw(path);

            // Arrowhead at destination (pointing up into destination stage)
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.5f));
            int[] xp = {(int) p2x, (int) p2x - 4, (int) p2x + 4};
            int[] yp = {(int) p2y - 7, (int) p2y + 1, (int) p2y + 1};
            g2.fillPolygon(xp, yp, 3);

            // Label at arc midpoint
            float[] mid = quadBezier(p0x, p0y, p1x, p1y, p2x, p2y, 0.5f);
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
            g2.setColor(color.darker());
            FontMetrics lfm = g2.getFontMetrics();
            int lw = lfm.stringWidth(label);
            // White background for readability
            g2.setColor(new Color(236, 240, 241, 200));
            g2.fillRect((int) mid[0] - lw / 2 - 1, (int) mid[1] - lfm.getAscent(), lw + 2, lfm.getHeight());
            g2.setColor(color.darker());
            g2.drawString(label, mid[0] - lw / 2f, mid[1]);

            // Animated dot
            if (dotActive && dotT > 0f) {
               float[] dp = quadBezier(p0x, p0y, p1x, p1y, p2x, p2y, dotT);
               g2.setColor(color);
               g2.setStroke(new BasicStroke(1f));
               g2.fillOval((int) dp[0] - 5, (int) dp[1] - 5, 10, 10);
               g2.setColor(Color.WHITE);
               g2.setStroke(new BasicStroke(1.2f));
               g2.drawOval((int) dp[0] - 5, (int) dp[1] - 5, 10, 10);
            }
         }

         /** Evaluate a quadratic Bezier at parameter t. */
         private static float[] quadBezier(float p0x, float p0y,
                                            float p1x, float p1y,
                                            float p2x, float p2y, float t) {
            float mt = 1 - t;
            return new float[]{
               mt * mt * p0x + 2 * mt * t * p1x + t * t * p2x,
               mt * mt * p0y + 2 * mt * t * p1y + t * t * p2y
            };
         }
      }

      // ═══════════════════════════════════════════════════════════════════════
      // Inner: timing-table cell renderer
      // ═══════════════════════════════════════════════════════════════════════

      private class TimingRenderer extends DefaultTableCellRenderer {

         @Override
         public Component getTableCellRendererComponent(
               JTable table, Object value, boolean sel, boolean foc, int row, int col) {
            super.getTableCellRendererComponent(table, value, sel, foc, row, col);
            setHorizontalAlignment(col == 0 ? CENTER : LEFT);

            if (!sel) {
               int evType = (row < rowEvents.size()) ? rowEvents.get(row) : EV_NORMAL;

               if (col == 0) {
                  // Row-number column: tint by event type
                  Color rowBg = evType == EV_STALL   ? new Color(255, 220, 150) :
                                evType == EV_FLUSH   ? new Color(255, 180, 180) :
                                evType == EV_FORWARD ? new Color(200, 230, 255) :
                                                       new Color(213, 216, 220);
                  setBackground(rowBg);
                  setForeground(new Color(44, 62, 80));
                  setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
               } else {
                  String  txt = value != null ? value.toString() : "";
                  boolean bub = "—".equals(txt) || txt.isEmpty();
                  if (bub) {
                     // Colour bubble cells by event type
                     Color bubBg = evType == EV_STALL  ? new Color(255, 235, 195) :
                                   evType == EV_FLUSH  ? new Color(255, 210, 210) :
                                                         new Color(250, 250, 250);
                     Color bubFg = evType == EV_STALL  ? new Color(150,  80,   0) :
                                   evType == EV_FLUSH  ? new Color(150,   0,   0) :
                                                         new Color(180, 180, 180);
                     setBackground(bubBg);
                     setForeground(bubFg);
                  } else {
                     setBackground(lighten(STAGE_COLORS[col - 1], 0.72f));
                     setForeground(new Color(33, 33, 33));
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
