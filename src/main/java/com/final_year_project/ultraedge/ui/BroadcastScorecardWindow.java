package com.final_year_project.ultraedge.ui;

import com.final_year_project.ultraedge.ui.CricketScorecardApp.Batsman;
import com.final_year_project.ultraedge.ui.CricketScorecardApp.Bowler;
import com.final_year_project.ultraedge.ui.CricketScorecardApp.MatchState;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import java.awt.*;

public class BroadcastScorecardWindow extends JFrame {

    private final MatchState match;
    private final Runnable onUpdateCallback;

    // Bright Broadcast Stadium Palette
    private static final Color HEADER_NAVY = new Color(15, 33, 64);
    private static final Color TABLE_HEADER_BLUE = new Color(24, 52, 98);
    private static final Color ROW_WHITE = new Color(255, 255, 255);
    private static final Color ROW_ZEBRA = new Color(243, 246, 252);
    private static final Color ACTIVE_BATTER_BG = new Color(37, 99, 235);
    private static final Color ACTIVE_BOWLER_BG = new Color(220, 252, 231);
    private static final Color TEXT_DARK = new Color(15, 23, 42);
    private static final Color BORDER_GRAY = new Color(226, 232, 240);

    public BroadcastScorecardWindow(MatchState match, Runnable onUpdateCallback) {
        super("Official Match Broadcast Scorecard - " + match.battingTeam + " vs " + match.bowlingTeam);
        this.match = match;
        this.onUpdateCallback = onUpdateCallback;

        setSize(1020, 740);
        setMinimumSize(new Dimension(880, 620));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        getContentPane().setBackground(new Color(241, 245, 249));
        setLayout(new BorderLayout(0, 0));

        add(createHeaderPanel(), BorderLayout.NORTH);
        add(createScorecardTables(), BorderLayout.CENTER);
        add(createBottomToolbar(), BorderLayout.SOUTH);
    }

    private JPanel createHeaderPanel() {
        JPanel header = new JPanel(new BorderLayout(16, 6));
        header.setBackground(HEADER_NAVY);
        header.setBorder(new EmptyBorder(18, 24, 18, 24));

        JLabel teamLabel = new JLabel(match.battingTeam.toUpperCase());
        teamLabel.setFont(new Font("Impact", Font.PLAIN, 36));
        teamLabel.setForeground(Color.WHITE);

        String scoreText = match.totalRuns + " - " + match.totalWickets + "  (" + match.getOversFormatted() + " / " + match.totalOvers + " Ov)";
        JLabel scoreLabel = new JLabel(scoreText, JLabel.RIGHT);
        scoreLabel.setFont(new Font("Segoe UI", Font.BOLD, 26));
        scoreLabel.setForeground(new Color(34, 197, 94));

        String crrText = String.format("CRR: %.2f", match.getRunRate());
        if (match.target != -1) {
            crrText += "   |   TARGET: " + match.target;
        }
        JLabel subLabel = new JLabel(crrText);
        subLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
        subLabel.setForeground(new Color(56, 189, 248));

        JLabel extrasLabel = new JLabel("EXTRAS CONCEDED: " + match.extrasConceded, JLabel.RIGHT);
        extrasLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        extrasLabel.setForeground(new Color(203, 213, 225));

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(teamLabel, BorderLayout.WEST);
        top.add(scoreLabel, BorderLayout.EAST);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(subLabel, BorderLayout.WEST);
        bottom.add(extrasLabel, BorderLayout.EAST);

        header.add(top, BorderLayout.NORTH);
        header.add(bottom, BorderLayout.SOUTH);
        return header;
    }

    private JPanel createScorecardTables() {
        JPanel container = new JPanel(new GridLayout(2, 1, 0, 14));
        container.setOpaque(false);
        container.setBorder(new EmptyBorder(12, 18, 12, 18));

        // 1. Batting Table
        String[] batCols = {"BATTING CARD", "DISMISSAL", "R", "B", "4s", "6s", "SR"};
        DefaultTableModel batModel = new DefaultTableModel(batCols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };

        for (int i = 0; i < match.batsmen.size(); i++) {
            Batsman b = match.batsmen.get(i);
            String status;
            String nameCol = b.name;
            if (b.isOut) {
                status = b.dismissalText.isEmpty() ? "out" : b.dismissalText;
            } else if (i == match.strikerIndex) {
                status = "not out *";
                nameCol = b.name + " *";
            } else if (i == match.nonStrikerIndex) {
                status = "not out";
            } else {
                status = "yet to bat";
            }

            batModel.addRow(new Object[]{
                    nameCol,
                    status,
                    b.runs,
                    b.balls,
                    b.fours,
                    b.sixes,
                    String.format("%.1f", b.getStrikeRate())
            });
        }

        JTable batTable = styleBrightTable(new JTable(batModel), true);
        JScrollPane batScroll = new JScrollPane(batTable);
        batScroll.getViewport().setBackground(Color.WHITE);
        batScroll.setBorder(BorderFactory.createLineBorder(BORDER_GRAY, 1));

        // 2. Bowling Table
        String[] bowlCols = {"BOWLING FIGURES (" + match.bowlingTeam.toUpperCase() + ")", "O", "M", "R", "W", "ECON"};
        DefaultTableModel bowlModel = new DefaultTableModel(bowlCols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };

        for (int i = 0; i < match.bowlers.size(); i++) {
            Bowler bw = match.bowlers.get(i);
            if (bw.ballsBowled > 0 || i == match.currentBowlerIndex) {
                String nameMarker = (i == match.currentBowlerIndex) ? bw.name + " (Active)" : bw.name;
                bowlModel.addRow(new Object[]{
                        nameMarker,
                        bw.getOversFormatted(),
                        0,
                        bw.runsConceded,
                        bw.wicketsTaken,
                        String.format("%.2f", bw.getEconomy())
                });
            }
        }

        JTable bowlTable = styleBrightTable(new JTable(bowlModel), false);
        JScrollPane bowlScroll = new JScrollPane(bowlTable);
        bowlScroll.getViewport().setBackground(Color.WHITE);
        bowlScroll.setBorder(BorderFactory.createLineBorder(BORDER_GRAY, 1));

        container.add(batScroll);
        container.add(bowlScroll);
        return container;
    }

    private JTable styleBrightTable(JTable table, boolean isBatting) {
        table.setFont(new Font("Segoe UI", Font.BOLD, 13));
        table.setRowHeight(32);
        table.setShowGrid(true);
        table.setGridColor(BORDER_GRAY);
        table.setFillsViewportHeight(true);

        // Custom Header Renderer to stop Swing white header bugs
        JTableHeader header = table.getTableHeader();
        header.setPreferredSize(new Dimension(0, 36));
        header.setReorderingAllowed(false);
        header.setDefaultRenderer((t, val, isSel, hasFoc, row, col) -> {
            JLabel lbl = new JLabel(val != null ? val.toString() : "");
            lbl.setOpaque(true);
            lbl.setBackground(TABLE_HEADER_BLUE);
            lbl.setForeground(Color.WHITE);
            lbl.setFont(new Font("Segoe UI", Font.BOLD, 12));
            lbl.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 1, new Color(40, 70, 120)),
                    new EmptyBorder(0, 10, 0, 10)
            ));
            lbl.setHorizontalAlignment(col == 0 ? JLabel.LEFT : JLabel.CENTER);
            return lbl;
        });

        // Alternating Rows & Active Highlighting
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object val, boolean isSel, boolean hasFoc, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, val, isSel, hasFoc, row, col);
                setHorizontalAlignment(col == 0 ? JLabel.LEFT : JLabel.CENTER);
                setBorder(new EmptyBorder(0, 10, 0, 10));

                boolean isActiveRow = false;
                if (isBatting) {
                    if (row == match.strikerIndex) {
                        isActiveRow = true;
                        c.setBackground(ACTIVE_BATTER_BG);
                        c.setForeground(Color.WHITE);
                    }
                } else {
                    String firstCol = (String) t.getValueAt(row, 0);
                    if (firstCol != null && firstCol.contains("(Active)")) {
                        isActiveRow = true;
                        c.setBackground(ACTIVE_BOWLER_BG);
                        c.setForeground(new Color(20, 83, 45));
                    }
                }

                if (!isActiveRow) {
                    c.setBackground(row % 2 == 0 ? ROW_WHITE : ROW_ZEBRA);
                    c.setForeground(TEXT_DARK);
                }

                return c;
            }
        });

        return table;
    }

    private JPanel createBottomToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 12));
        bar.setBackground(new Color(241, 245, 249));
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_GRAY));

        JButton btnClose = new JButton("Close Scorecard") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(HEADER_NAVY);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                g2.setColor(Color.WHITE);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int x = (getWidth() - fm.stringWidth(getText())) / 2;
                int y = (getHeight() + fm.getAscent()) / 2 - 2;
                g2.drawString(getText(), x, y);
                g2.dispose();
            }
        };
        btnClose.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btnClose.setPreferredSize(new Dimension(150, 38));
        btnClose.setFocusPainted(false);
        btnClose.setContentAreaFilled(false);
        btnClose.setBorderPainted(false);
        btnClose.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnClose.addActionListener(e -> dispose());

        bar.add(btnClose);
        return bar;
    }
}