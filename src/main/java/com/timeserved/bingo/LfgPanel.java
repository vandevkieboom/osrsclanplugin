package com.timeserved.bingo;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * The "LFG" side panel: post/cancel your own looking-for-group ad and see
 * everyone else's open ones. This talks to the clan site the same way the
 * rest of the plugin does (via {@link BingoApiClient}, gated by the same
 * plugin key as bingo) — there is no separate party protocol or website page
 * behind this, just one small polled endpoint.
 */
@Slf4j
public class LfgPanel extends PluginPanel
{
	/** Display label -> the DB/wire value it maps to. Order here is the dropdown's order. */
	private static final Map<String, String> ACTIVITIES = new LinkedHashMap<>();

	static
	{
		ACTIVITIES.put("Theatre of Blood", "tob");
		ACTIVITIES.put("Chambers of Xeric", "cox");
		ACTIVITIES.put("Tombs of Amascut", "toa");
		ACTIVITIES.put("Inferno", "inferno");
		ACTIVITIES.put("Fight Caves", "fight_caves");
		ACTIVITIES.put("Wintertodt", "wintertodt");
		ACTIVITIES.put("Skilling", "skilling");
		ACTIVITIES.put("Other", "other");
	}

	private final BingoApiClient api;
	private final BingoConfig config;

	private final JComboBox<String> activityCombo = new JComboBox<>(ACTIVITIES.keySet().toArray(new String[0]));
	private final JSpinner spotsSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 20, 1));
	private final JTextField noteField = new JTextField();
	private final JTextField passphraseField = new JTextField();
	private final JButton postButton = new JButton("Post");
	private final JButton cancelButton = new JButton("Cancel my post");
	private final JLabel statusLabel = new JLabel(" ");
	private final JPanel listPanel = new JPanel();

	/** Whichever post in the last refresh belonged to this account, if any. */
	private volatile String myPostId;

	@Inject
	public LfgPanel(BingoApiClient api, BingoConfig config)
	{
		super(false);
		this.api = api;
		this.config = config;

		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(8, 8, 8, 8));

		add(buildFormPanel(), BorderLayout.NORTH);

		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		JScrollPane scrollPane = new JScrollPane(listPanel);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		add(scrollPane, BorderLayout.CENTER);

		cancelButton.setEnabled(false);
		postButton.addActionListener(e -> onPostClicked());
		cancelButton.addActionListener(e -> onCancelClicked());

		refresh(Collections.emptyList());
	}

	private JPanel buildFormPanel()
	{
		JPanel form = new JPanel();
		form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

		JPanel activityRow = new JPanel(new GridLayout(1, 2, 4, 0));
		activityRow.add(activityCombo);
		activityRow.add(spotsSpinner);
		form.add(activityRow);

		form.add(Box.createVerticalStrut(4));
		noteField.setToolTipText("Optional note (e.g. \"learner friendly\")");
		form.add(labeled("Note", noteField));

		form.add(Box.createVerticalStrut(4));
		passphraseField.setToolTipText("Optional RuneLite party passphrase, so others can join with one paste");
		form.add(labeled("Party passphrase", passphraseField));

		form.add(Box.createVerticalStrut(6));
		JPanel buttonRow = new JPanel(new GridLayout(1, 2, 4, 0));
		buttonRow.add(postButton);
		buttonRow.add(cancelButton);
		form.add(buttonRow);

		statusLabel.setForeground(Color.LIGHT_GRAY);
		statusLabel.setHorizontalAlignment(SwingConstants.LEFT);
		form.add(statusLabel);

		form.add(Box.createVerticalStrut(6));
		return form;
	}

	private JPanel labeled(String label, JTextField field)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		JLabel l = new JLabel(label);
		l.setPreferredSize(new Dimension(90, l.getPreferredSize().height));
		row.add(l, BorderLayout.WEST);
		row.add(field, BorderLayout.CENTER);
		return row;
	}

	private void onPostClicked()
	{
		String apiKey = config.apiKey().trim();
		if (apiKey.isEmpty())
		{
			statusLabel.setText("Set a plugin key first (Configuration).");
			return;
		}

		String activityLabel = (String) activityCombo.getSelectedItem();
		String activity = ACTIVITIES.get(activityLabel);
		int spots = (Integer) spotsSpinner.getValue();
		String note = noteField.getText().trim();
		String passphrase = passphraseField.getText().trim();

		postButton.setEnabled(false);
		statusLabel.setText("Posting...");
		api.postLfg(apiKey, activity, spots, note, passphrase,
			() -> SwingUtilities.invokeLater(() -> {
				postButton.setEnabled(true);
				statusLabel.setText("Posted.");
				fetchAndRefresh();
			}),
			error -> SwingUtilities.invokeLater(() -> {
				postButton.setEnabled(true);
				statusLabel.setText(error);
			}));
	}

	private void onCancelClicked()
	{
		String apiKey = config.apiKey().trim();
		if (apiKey.isEmpty())
		{
			return;
		}

		cancelButton.setEnabled(false);
		statusLabel.setText("Cancelling...");
		api.cancelLfg(apiKey,
			() -> SwingUtilities.invokeLater(() -> {
				statusLabel.setText("Cancelled.");
				fetchAndRefresh();
			}),
			error -> SwingUtilities.invokeLater(() -> {
				cancelButton.setEnabled(true);
				statusLabel.setText(error);
			}));
	}

	/** Re-fetches immediately after this client's own post/cancel, rather than waiting for the next scheduled poll. */
	private void fetchAndRefresh()
	{
		String apiKey = config.apiKey().trim();
		if (apiKey.isEmpty())
		{
			return;
		}
		api.fetchLfgPosts(apiKey,
			posts -> SwingUtilities.invokeLater(() -> refresh(posts)),
			error -> log.debug("LFG refresh-after-action failed: {}", error));
	}

	/** Rebuilds the visible list from a fresh fetch. Must be called on the EDT. */
	void refresh(List<BingoApiClient.LfgPost> posts)
	{
		listPanel.removeAll();

		myPostId = null;
		for (BingoApiClient.LfgPost post : posts)
		{
			if (post.isMine)
			{
				myPostId = post.id;
			}
			listPanel.add(buildPostRow(post));
			listPanel.add(Box.createVerticalStrut(4));
		}
		cancelButton.setEnabled(myPostId != null);

		if (posts.isEmpty())
		{
			JLabel empty = new JLabel("No one is looking for a group right now.");
			empty.setForeground(Color.GRAY);
			listPanel.add(empty);
		}

		listPanel.revalidate();
		listPanel.repaint();
	}

	private JPanel buildPostRow(BingoApiClient.LfgPost post)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(4, 6, 4, 6)));

		String activityLabel = ACTIVITIES.entrySet().stream()
			.filter(e -> e.getValue().equals(post.activity))
			.map(Map.Entry::getKey)
			.findFirst()
			.orElse(post.activity);

		JLabel title = new JLabel(activityLabel + " · needs " + post.spotsNeeded);
		title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));
		row.add(title);

		JLabel poster = new JLabel(post.postedBy + (post.isMine ? " (you)" : ""));
		poster.setForeground(Color.LIGHT_GRAY);
		row.add(poster);

		if (post.note != null && !post.note.isEmpty())
		{
			JLabel note = new JLabel(post.note);
			row.add(note);
		}

		if (post.partyPassphrase != null && !post.partyPassphrase.isEmpty())
		{
			JPanel passRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
			JTextField passField = new JTextField(post.partyPassphrase);
			passField.setEditable(false);
			passField.setColumns(Math.max(8, post.partyPassphrase.length()));
			passRow.add(new JLabel("Party: "));
			passRow.add(passField);
			row.add(passRow);
		}

		return row;
	}
}
