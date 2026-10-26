package com.final_year_project.ultraedge;

import com.final_year_project.ultraedge.ui.CricketScorecardApp;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import javax.swing.*;

@SpringBootApplication
public class UltraedgeApplication implements CommandLineRunner {

	public static void main(String[] args) {
		System.setProperty("java.awt.headless", "false");
		SpringApplication.run(UltraedgeApplication.class, args);
	}

	@Override
	public void run(String... args) {
		SwingUtilities.invokeLater(() -> {
			try {
				UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
			} catch (Exception ignored) {}

			CricketScorecardApp scorecardApp = new CricketScorecardApp();
			scorecardApp.setVisible(true);
		});
	}
}