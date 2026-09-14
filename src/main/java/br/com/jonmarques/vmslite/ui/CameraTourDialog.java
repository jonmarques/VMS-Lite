package br.com.jonmarques.vmslite.ui;

import br.com.jonmarques.vmslite.entity.Camera;
import br.com.jonmarques.vmslite.entity.CameraTourConfig;
import br.com.jonmarques.vmslite.service.CameraAddress;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

public final class CameraTourDialog {
    private CameraTourDialog() {}

    public static CameraTourConfig show(Component owner, List<Camera> cameras, CameraTourConfig existing) {
        JCheckBox enabled = new JCheckBox("Ativar tour", existing.isEnabled());
        JSpinner seconds = new JSpinner(new SpinnerNumberModel(existing.getIntervalSeconds(),
                CameraTourConfig.MIN_INTERVAL_SECONDS, CameraTourConfig.MAX_INTERVAL_SECONDS, 5));
        JPanel settings = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        settings.add(enabled);
        settings.add(new JLabel("Intervalo (segundos):"));
        settings.add(seconds);
        List<Camera> ordered = new ArrayList<>();
        for (String id : existing.getCameraIds()) {
            cameras.stream().filter(camera -> camera.getId().equals(id)).findFirst().ifPresent(ordered::add);
        }
        for (Camera camera : cameras) if (!ordered.contains(camera)) ordered.add(camera);
        DefaultTableModel model = new DefaultTableModel(new String[]{"Tour", "Camera", "Endereco"}, 0) {
            @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
            @Override public boolean isCellEditable(int row, int column) { return column == 0; }
        };
        for (Camera camera : ordered) {
            model.addRow(new Object[]{existing.getCameraIds().contains(camera.getId()),
                    camera.getName(), CameraAddress.host(camera.getUrl())});
        }
        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(Math.max(26, table.getRowHeight()));
        table.getColumnModel().getColumn(0).setMaxWidth(55);
        table.getTableHeader().setReorderingAllowed(false);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(530, 270));
        JButton up = new JButton("\u2191");
        JButton down = new JButton("\u2193");
        up.setToolTipText("Mover camera para cima na ordem do tour");
        down.setToolTipText("Mover camera para baixo na ordem do tour");
        up.addActionListener(event -> move(table, model, ordered, -1));
        down.addActionListener(event -> move(table, model, ordered, 1));
        JPanel order = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        order.add(up);
        order.add(down);
        JPanel panel = new JPanel(new BorderLayout(8, 12));
        panel.add(settings, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(order, BorderLayout.SOUTH);
        while (JOptionPane.showConfirmDialog(owner, panel, "Camera Tour - 2 x 2",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            try { seconds.commitEdit(); }
            catch (java.text.ParseException error) {
                JOptionPane.showMessageDialog(owner, "Informe um intervalo de 5 a 3600 segundos.");
                continue;
            }
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < model.getRowCount(); i++) {
                if (Boolean.TRUE.equals(model.getValueAt(i, 0))) ids.add(ordered.get(i).getId());
            }
            if (enabled.isSelected() && ids.isEmpty()) {
                JOptionPane.showMessageDialog(owner, "Selecione ao menos uma camera para o tour.");
                continue;
            }
            return new CameraTourConfig(enabled.isSelected(), ((Number) seconds.getValue()).intValue(), ids);
        }
        return null;
    }

    private static void move(JTable table, DefaultTableModel model, List<Camera> cameras, int offset) {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        int row = table.getSelectedRow();
        int destination = row + offset;
        if (row < 0 || destination < 0 || destination >= model.getRowCount()) return;
        model.moveRow(row, row, destination);
        java.util.Collections.swap(cameras, row, destination);
        table.setRowSelectionInterval(destination, destination);
    }
}
