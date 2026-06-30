package br.com.jonmarques.vmslite.listener;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.*;

import br.com.jonmarques.vmslite.CameraPanel;
import br.com.jonmarques.vmslite.VMSLite;

public class GridDragListener extends MouseAdapter {

    private final VMSLite vmsLite;
    private final CameraPanel panel;
    private Point startClickPoint = null;

    public GridDragListener(VMSLite vmsLite, CameraPanel panel) {
        this.vmsLite = vmsLite;
        this.panel = panel;
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (SwingUtilities.isRightMouseButton(e)) return;

        // Inicia o ponto de clique baseado na tela global
        startClickPoint = e.getLocationOnScreen();

        // Diz ao painel da câmera para entrar em modo de arrasto (esconder o player nativo)
        panel.setArrastando(true);

        // Joga o componente para a frente visual do container
        panel.getParent().setComponentZOrder(panel, 0);
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (startClickPoint == null) return;

        Point currentTarget = e.getLocationOnScreen();
        int deltaX = currentTarget.x - startClickPoint.x;
        int deltaY = currentTarget.y - startClickPoint.y;

        // Move o painel somando o deslocamento
        int newX = panel.getX() + deltaX;
        int newY = panel.getY() + deltaY;
        panel.setLocation(newX, newY);

        // Atualiza o ponto de partida para o próximo frame do movimento
        startClickPoint = currentTarget;

        panel.getParent().repaint();
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (startClickPoint == null) return;

        JPanel parent = (JPanel) panel.getParent();
        // Converte o ponto final do mouse para as coordenadas do painel pai
        Point releasePoint = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), parent);

        CameraPanel targetPanel = null;
        for (Component c : parent.getComponents()) {
            if (c instanceof CameraPanel && c != panel && c.getBounds().contains(releasePoint)) {
                targetPanel = (CameraPanel) c;
                break;
            }
        }

        // Desativa o modo de arrasto (reexibe o player)
        panel.setArrastando(false);

        if (targetPanel != null) {
            vmsLite.reordenarCameras(panel, targetPanel);
        } else {
            vmsLite.rebuildLayout();
        }

        startClickPoint = null;
    }
}