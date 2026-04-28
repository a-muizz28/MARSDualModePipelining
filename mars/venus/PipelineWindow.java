package mars.venus;

import mars.Globals;

import javax.swing.*;
import java.awt.*;
import java.net.URL;

/**
 * Floating popup window that displays the MIPS 5-stage pipeline
 * visualization in a large, dedicated frame.
 *
 * <p>The window is a singleton; call {@link #getInstance()} to obtain it.
 * It auto-shows and updates every time a pipeline cycle completes via
 * {@link #showAndUpdate()}, and clears itself on program reset via
 * {@link #resetDiagram()}.
 */
public class PipelineWindow extends JFrame {

   private static PipelineWindow instance;

   private final PipelineDiagramPanel diagramPanel;

   // ── Singleton access ────────────────────────────────────────────────────

   public static synchronized PipelineWindow getInstance() {
      if (instance == null) {
         instance = new PipelineWindow();
      }
      return instance;
   }

   // ── Constructor ─────────────────────────────────────────────────────────

   private PipelineWindow() {
      super("MIPS 5-Stage Pipeline  —  Visualization");

      // Mars icon
      URL iconURL = getClass().getResource(Globals.imagesPath + "RedMars16.gif");
      if (iconURL != null) {
         setIconImage(Toolkit.getDefaultToolkit().getImage(iconURL));
      }

      setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
      setMinimumSize(new Dimension(860, 480));
      setPreferredSize(new Dimension(1120, 600));

      // ── Diagram panel (initialised first so toolbar lambdas can capture it)
      diagramPanel = new PipelineDiagramPanel();

      // ── Toolbar ──────────────────────────────────────────────────────────
      JToolBar toolbar = new JToolBar();
      toolbar.setFloatable(false);
      toolbar.setBackground(new Color(44, 62, 80));
      toolbar.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));

      JButton clearBtn = styledButton("Clear Log", new Color(231, 76, 60));
      clearBtn.setToolTipText("Clear the cycle timing log");
      clearBtn.addActionListener(e -> diagramPanel.reset());

      JToggleButton topBtn = styledToggle("Always on Top");
      topBtn.setToolTipText("Keep this window above all others");
      topBtn.addActionListener(e -> setAlwaysOnTop(topBtn.isSelected()));

      JLabel hint = new JLabel("  Use  Run → Step Cycle  (F6)  to advance the pipeline one cycle at a time.");
      hint.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 11));
      hint.setForeground(new Color(189, 195, 199));

      toolbar.add(clearBtn);
      toolbar.addSeparator(new Dimension(8, 0));
      toolbar.add(topBtn);
      toolbar.add(Box.createHorizontalStrut(16));
      toolbar.add(hint);
      toolbar.add(Box.createHorizontalGlue());

      // ── Layout ───────────────────────────────────────────────────────────
      setLayout(new BorderLayout());
      add(toolbar,       BorderLayout.NORTH);
      add(diagramPanel,  BorderLayout.CENTER);

      pack();
      setLocationRelativeTo(null); // centre on screen at first open
   }

   // ── Public API ──────────────────────────────────────────────────────────

   /**
    * Refresh the diagram with the latest cycle data, make the window
    * visible if it is not already, and bring it to the front.
    */
   public void showAndUpdate() {
      diagramPanel.update();
      if (!isVisible()) {
         setVisible(true);
      }
      toFront();
   }

   /** Clear the diagram and timing log (called when the program resets). */
   public void resetDiagram() {
      diagramPanel.reset();
   }

   // ── Helpers ─────────────────────────────────────────────────────────────

   private static JButton styledButton(String text, Color accent) {
      JButton b = new JButton(text);
      b.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
      b.setForeground(Color.WHITE);
      b.setBackground(accent);
      b.setOpaque(true);
      b.setBorderPainted(false);
      b.setFocusPainted(false);
      b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      return b;
   }

   private static JToggleButton styledToggle(String text) {
      JToggleButton b = new JToggleButton(text);
      b.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
      b.setForeground(Color.WHITE);
      b.setBackground(new Color(52, 73, 94));
      b.setOpaque(true);
      b.setBorderPainted(false);
      b.setFocusPainted(false);
      b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      return b;
   }
}
