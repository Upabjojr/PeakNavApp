package com.peaknav.viewer.desktop;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;

import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.LineBorder;

// TODO: taken from https://stackoverflow.com/questions/10161149/android-like-toast-in-swing
public class ToastDesktop extends JDialog {
    private int millisecs;

    public ToastDesktop(String toastString, int millisecs) {
        this.millisecs = millisecs;
        setUndecorated(true);
        getContentPane().setLayout(new BorderLayout(0, 0));

        JPanel panel = new JPanel();
        panel.setBackground(Color.GRAY);
        panel.setBorder(new LineBorder(Color.LIGHT_GRAY, 2));
        getContentPane().add(panel, BorderLayout.CENTER);

        JLabel toastLabel = new JLabel("");
        toastLabel.setText(toastString);
        toastLabel.setFont(new Font("Dialog", Font.BOLD, 12));
        toastLabel.setForeground(Color.WHITE);

        setBounds(100, 100, toastLabel.getPreferredSize().width+20, 31);

        setAlwaysOnTop(true);
        Dimension dim = Toolkit.getDefaultToolkit().getScreenSize();
        int y = dim.height/2-getSize().height/2;
        int half = y/2;
        setLocation(dim.width/2-getSize().width/2, y+half);
        panel.add(toastLabel);
        setVisible(false);

        new Thread() {
            public void run() {
                try {
                    Thread.sleep(ToastDesktop.this.millisecs);
                    dispose();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }.start();
    }

}
