package info.openrocket.swing.gui.simulation;

import java.awt.Dimension;
import java.awt.Window;

import javax.swing.JDialog;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;

/**
 * A dialog window that hosts a {@link SimulationReplayPanel} for 3D flight replay.
 */
public class SimulationReplayDialog extends JDialog {

    public SimulationReplayDialog(Window parent, OpenRocketDocument document, Simulation simulation) {
        super(parent, "3D Replay — " + simulation.getName(), ModalityType.MODELESS);

        SimulationReplayPanel replayPanel = new SimulationReplayPanel(document, simulation);
        setContentPane(replayPanel);

        // GLJPanel reports zero preferred height before first render; pack() would
        // collapse the dialog. setSize() bypasses preferred-size negotiation entirely.
        setSize(900, 700);
        setMinimumSize(new Dimension(600, 400));
        setLocationRelativeTo(parent);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                replayPanel.cleanup();
            }
        });
    }
}
