package com.github.ygimenez.method;

import com.github.ygimenez.exception.AlreadyActivatedException;
import com.github.ygimenez.exception.InvalidHandlerException;
import com.github.ygimenez.exception.InvalidStateException;
import com.github.ygimenez.listener.MessageHandler;
import com.github.ygimenez.model.*;
import com.github.ygimenez.type.ButtonOp;
import com.github.ygimenez.util.Utils;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Emoji;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.ButtonClickEvent;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.Button;
import net.dv8tion.jda.api.sharding.ShardManager;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class Pages {
	private static final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	private static final MessageHandler handler = new MessageHandler();
	private static Paginator paginator;

	public enum Mode {
		PAGINATE, CATEGORIZE, BUTTONIZE
	}

	public static void activate(@Nonnull Paginator paginator) throws InvalidHandlerException {
		if (isActivated())
			throw new AlreadyActivatedException();

		Object hand = paginator.getHandler();
		if (hand instanceof JDA)
			((JDA) hand).addEventListener(handler);
		else if (hand instanceof ShardManager)
			((ShardManager) hand).addEventListener(handler);
		else throw new InvalidHandlerException();

		Pages.paginator = paginator;
	}

	public static void deactivate() {
		if (!isActivated())
			return;

		Object hand = paginator.getHandler();
		if (hand instanceof JDA)
			((JDA) hand).removeEventListener(handler);
		else if (hand instanceof ShardManager)
			((ShardManager) hand).removeEventListener(handler);

		paginator = null;
	}

	public static boolean isActivated() {
		return paginator != null && paginator.getHandler() != null;
	}

	public static Paginator getPaginator() {
		return paginator;
	}

	public static MessageHandler getHandler() {
		return handler;
	}

	public static void paginate(final Operator op, int skip) {
		if (!isActivated()) throw new InvalidStateException();

		List<ActionRow> rows = new ArrayList<>();
		rows.add(ActionRow.of(getPaginationNav(op, 0)));

		Page pg = Utils.getOr(op.getHome(), op.getPages().get(0));
		if (pg.hasButtons())
			rows.addAll(pg.getActionRows());

		(pg.getContent() instanceof Message
				? op.getMessage().editMessage((Message) pg.getContent())
				: op.getMessage().editMessage((MessageEmbed) pg.getContent())
		).setActionRows(rows)
				.submit()
				.thenAccept(msg -> handler.addEvent(msg.getChannel().getId() + msg.getId(),
						new Consumer<>() {
							private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
							private final int maxP = op.getPages().size() - 1;
							private final Consumer<Void> success = s -> {
								handler.removeEvent(msg);

								if (timeout.get() != null)
									timeout.get().cancel(true);
								if (op.getOnClose() != null)
									op.getOnClose().accept(op.getMessage());
								if (paginator.isDeleteOnCancel())
									msg.delete().submit();
							};
							private final Map<String, Action> fixedButtons = getButtonRows(op);
							private Map<String, Action> pageButtons = pg.hasButtons() ? pg.getButtonsAsMap() : Map.of();
							private int p = 0;

							{
								if (op.getUnit() != null)
									setTimeout(timeout, success, msg, op.getTime(), op.getUnit());
							}


							@Override
							public void accept(ButtonClickEvent evt) {
								Message msg = evt.getMessage();
								String id = evt.getComponentId();

								if (evt.getUser().isBot()
									|| msg == null
									|| !op.getValidation().test(evt.getMessage(), evt.getUser())
								) return;

								Action a = fixedButtons.getOrDefault(id, pageButtons.get(id));
								if (a != null) {
									if (a.getType() == ButtonOp.CUSTOM) {
										evt.deferEdit().submit()
												.thenRun(() -> a.getEvent().accept(msg, evt.getUser()));
										return;
									} else {
										id = a.getType().name();
									}
								}

								switch (id) {
									case "CANCEL":
										evt.editComponents(new ActionRow[0])
												.submit()
												.thenRun(() -> success.accept(null));
										return;
									case "NEXT":
										p = Math.min(p + 1, maxP);
										break;
									case "PREVIOUS":
										p = Math.max(p - 1, 0);
										break;
									case "SKIP_FORWARD":
										p = Math.min(p + skip, maxP);
										break;
									case "SKIP_BACKWARD":
										p = Math.max(p - skip, 0);
										break;
									case "GOTO_FIRST":
										p = 0;
										break;
									case "GOTO_LAST":
										p = maxP;
										break;
									default:
										return;
								}

								rows.clear();
								rows.add(ActionRow.of(getPaginationNav(op, p)));

								Page pg = op.getPages().get(p);
								if (pg.hasButtons()) {
									rows.addAll(pg.getActionRows());
									pageButtons = pg.getButtonsAsMap();
								}

								if (pg.getContent() instanceof Message)
									evt.editComponents(rows)
											.setContent(pg.toString())
											.submit();
								else
									evt.editComponents(rows)
											.setEmbeds((MessageEmbed) pg.getContent())
											.submit();
							}
						}
				));
	}

	public static void categorize(final Operator op, @Nonnull Map<Emoji, Integer> groupIndexes) {
		if (!isActivated()) throw new InvalidStateException();

		List<ActionRow> rows = new ArrayList<>();
		Map<Emoji, Page> cats = new HashMap<>();
		for (Page page : op.getPages()) {
			cats.put(page.getGroup(), page);
		}

		List<List<Button>> buttons = Utils.chunkify(getCategorizationNav(op, cats, groupIndexes, null), 5);
		for (List<Button> row : buttons) {
			rows.add(ActionRow.of(row));
		}

		Page pg = Utils.getOr(op.getHome(), op.getPages().get(0));
		if (pg.hasButtons())
			rows.addAll(pg.getActionRows());

		(pg.getContent() instanceof Message
				? op.getMessage().editMessage((Message) pg.getContent())
				: op.getMessage().editMessage((MessageEmbed) pg.getContent())
		).setActionRows(rows)
				.submit()
				.thenAccept(msg -> handler.addEvent(msg.getChannel().getId() + msg.getId(),
						new Consumer<>() {
							private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
							private final Consumer<Void> success = s -> {
								handler.removeEvent(msg);

								if (timeout.get() != null)
									timeout.get().cancel(true);
								if (op.getOnClose() != null)
									op.getOnClose().accept(op.getMessage());
								if (paginator.isDeleteOnCancel())
									msg.delete().submit();
							};
							private final Map<String, Action> fixedButtons = getButtonRows(op);
							private Map<String, Action> pageButtons = pg.hasButtons() ? pg.getButtonsAsMap() : Map.of();
							private Emoji currCat = pg.getGroup();

							{
								if (op.getUnit() != null)
									setTimeout(timeout, success, msg, op.getTime(), op.getUnit());
							}

							@Override
							public void accept(ButtonClickEvent evt) {
								Message msg = evt.getMessage();
								String id = evt.getComponentId();

								assert evt.getButton() != null;
								Emoji emj = evt.getButton().getEmoji();

								if (evt.getUser().isBot()
									|| msg == null
									|| (emj != null && currCat == emj)
									|| !op.getValidation().test(evt.getMessage(), evt.getUser())
								) return;

								Action a = fixedButtons.getOrDefault(id, pageButtons.get(id));
								if (a != null) {
									if (a.getType() == ButtonOp.CUSTOM) {
										evt.deferEdit().submit()
												.thenRun(() -> a.getEvent().accept(msg, evt.getUser()));
										return;
									} else {
										id = a.getType().name();
									}
								}

								if (id.equals("CANCEL")) {
									evt.editComponents(new ActionRow[0])
											.submit()
											.thenRun(() -> success.accept(null));
									return;
								}

								currCat = emj;

								rows.clear();
								List<List<Button>> buttons = Utils.chunkify(getCategorizationNav(op, cats, groupIndexes, currCat), 5);
								for (List<Button> row : buttons) {
									rows.add(ActionRow.of(row));
								}

								Page pg = cats.get(currCat);
								if (pg.hasButtons()) {
									rows.addAll(pg.getActionRows());
									pageButtons = pg.getButtonsAsMap();
								}

								if (pg.getContent() instanceof Message)
									evt.editComponents(rows)
											.setContent(pg.toString())
											.submit();
								else
									evt.editComponents(rows)
											.setEmbeds((MessageEmbed) pg.getContent())
											.submit();
							}
						}
				));
	}

	private static List<Button> getPaginationNav(Operator op, int p) {
		LinkedList<ButtonOp> buttons = new LinkedList<>();

		if (op.isShowCancel())
			buttons.add(ButtonOp.CANCEL);

		buttons.addFirst(ButtonOp.PREVIOUS);
		buttons.addLast(ButtonOp.NEXT);

		switch (op.getFlags().get(Mode.PAGINATE)) {
			case PAGINATE_WITH_SKIP:
				buttons.addFirst(ButtonOp.SKIP_BACKWARD);

				buttons.addLast(ButtonOp.SKIP_FORWARD);
				break;
			case PAGINATE_WITH_FF:
				buttons.addFirst(ButtonOp.GOTO_FIRST);

				buttons.addLast(ButtonOp.GOTO_LAST);
				break;
		}

		return buttons.stream()
				.map(b -> {
					Style s = paginator.getEmotes().get(b);
					Button btn = Button.of(s.getStyle(),
							b.name(),
							s.getLabel(),
							s.getEmoji()
					);

					switch (b) {
						case NEXT:
						case SKIP_FORWARD:
						case GOTO_FIRST:
							return btn.withDisabled(p == op.getPages().size() - 1);
						case PREVIOUS:
						case SKIP_BACKWARD:
						case GOTO_LAST:
							return btn.withDisabled(p == 0);
						default:
							return btn;
					}
				})
				.collect(Collectors.toList());
	}

	private static List<Button> getCategorizationNav(Operator op, Map<Emoji, Page> cats, Map<Emoji, Integer> groupIndexes, Emoji currCat) {
		LinkedList<Button> buttons = cats.entrySet().stream()
				.sorted(Comparator.comparingInt(e -> groupIndexes.getOrDefault(e.getKey(), -1)))
				.map(e -> Button.secondary(e.getKey().getAsMention(), e.getValue().getGroup()))
				.collect(Collectors.toCollection(LinkedList::new));

		if (op.isShowCancel()) {
			Style s = paginator.getEmotes().get(ButtonOp.CANCEL);
			buttons.addFirst(Button.of(s.getStyle(),
					ButtonOp.CANCEL.name(),
					s.getLabel(),
					s.getEmoji()
			));
		}

		return buttons.stream()
				.map(b -> b.getEmoji() == currCat ? b.asDisabled() : b.withDisabled(false))
				.collect(Collectors.toList());
	}

	private static Map<String, Action> getButtonRows(Operator op) {
		return op.getButtons().stream()
				.map(a -> Pair.of(a.getButton().getId(), a))
				.collect(Collectors.toMap(Pair::getLeft, Pair::getRight));
	}

	private static void setTimeout(AtomicReference<ScheduledFuture<?>> timeout, Consumer<Void> success, Message msg, int time, TimeUnit unit) {
		if (timeout.get() != null)
			timeout.get().cancel(true);

		timeout.set(
				executor.schedule(() -> {
					msg.editMessage(msg)
							.setActionRows(List.of())
							.submit()
							.thenRun(() -> success.accept(null));
				}, time, unit)
		);
	}
}
