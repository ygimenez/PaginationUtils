package com.github.ygimenez.method;

import com.coder4.emoji.EmojiUtils;
import com.github.ygimenez.exception.*;
import com.github.ygimenez.listener.MessageHandler;
import com.github.ygimenez.model.PUtilsConfig;
import com.github.ygimenez.model.Page;
import com.github.ygimenez.model.Paginator;
import com.github.ygimenez.model.ThrowingBiConsumer;
import com.github.ygimenez.type.Emote;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.message.react.GenericMessageReactionEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.sharding.ShardManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.github.ygimenez.type.Emote.*;

/**
 * The main class containing all pagination-related methods, including but not futurely limited
 * to {@link #paginate(Message, List)}, {@link #categorize(Message, Map)} and
 * {@link #buttonize(Message, Map, boolean)}.
 */
public class Pages {
	private static final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	private static final MessageHandler handler = new MessageHandler();
	private static Paginator paginator;

	/**
	 * Sets a {@link Paginator} object to handle incoming reactions. This is
	 * required only once unless you want to use another client as the handler. <br>
	 * <br>
	 * Before calling this method again, you must use {@link #deactivate()} to
	 * remove current {@link Paginator}, else this method will throw
	 * {@link AlreadyActivatedException}.
	 *
	 * @param paginator The {@link Paginator} object.
	 * @throws AlreadyActivatedException Thrown if there's a handler already set.
	 * @throws InvalidHandlerException   Thrown if the handler isn't either a {@link JDA}
	 *                                   or {@link ShardManager} object.
	 */
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
		paginator.log(PUtilsConfig.LogLevel.LEVEL_2, "Pagination Utils activated successfully");
	}

	/**
	 * Removes current button handler, allowing another {@link #activate(Paginator)} call. <br>
	 * <br>
	 * Using this method without activating beforehand will do nothing.
	 */
	public static void deactivate() {
		if (!isActivated())
			return;

		Object hand = paginator.getHandler();
		if (hand instanceof JDA)
			((JDA) hand).removeEventListener(handler);
		else if (hand instanceof ShardManager)
			((ShardManager) hand).removeEventListener(handler);

		paginator.log(PUtilsConfig.LogLevel.LEVEL_2, "Pagination Utils deactivated successfully");
		paginator = null;
	}

	/**
	 * Checks whether this library has been activated or not.
	 *
	 * @return The activation state of this library.
	 */
	public static boolean isActivated() {
		return paginator != null && paginator.getHandler() != null;
	}

	/**
	 * Retrieves the {@link Paginator} object used to activate this library.
	 *
	 * @return The current {@link Paginator} object.
	 */
	public static Paginator getPaginator() {
		return paginator;
	}

	/**
	 * Retrieves the library's {@link MessageHandler} object.
	 *
	 * @return The {@link MessageHandler} object.
	 */
	public static MessageHandler getHandler() {
		return handler;
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages.
	 *
	 * @param msg   The {@link Message} sent which will be paginated.
	 * @param pages The pages to be shown. The order of the {@link List} will define
	 *              the order of the pages.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pageList = Collections.unmodifiableList(pages);
		List<Emote> reactions = List.of(PREVIOUS, CANCEL, NEXT);
		clearReactions(msg);
		addReactions(msg, reactions);

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final int lastPageIndex = pageList.size() - 1;
			private int pageIndex = 0;
			private final Consumer<Void> successConsumer = s -> {
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			@Override
			public void acceptThrows(@Nonnull User user, @Nonnull GenericMessageReactionEvent event) {
				Message message;
				try {
					message = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
					return;
				}

				if (user.isBot() || !event.getMessageId().equals(msg.getId()))
					return;

				MessageReaction.ReactionEmote userReaction = event.getReactionEmote();
				reactions.forEach(reaction -> {
					if (checkEmote(userReaction, reaction)) {
						if (reaction.equals(Emote.CANCEL)) {
							reaction.doWork(message, successConsumer);
						} else {
							pageIndex = reaction.doWork(message, pageIndex, lastPageIndex, pageList);
						}
					}
				});

				if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
					event.getReaction().removeReaction(user).submit();
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages. You can specify
	 * how long the listener will stay active before shutting down itself after a
	 * no-activity interval.
	 *
	 * @param msg   The {@link Message} sent which will be paginated.
	 * @param pages The pages to be shown. The order of the {@link List} will define
	 *              the order of the pages.
	 * @param time  The time before the listener automatically stop listening for
	 *              further events (recommended: 60).
	 * @param unit  The time's {@link TimeUnit} (recommended: {@link TimeUnit#SECONDS}).
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, int time, @Nonnull TimeUnit unit) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);
		clearReactions(msg);

		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private final Consumer<Void> success = s -> {
				if (timeout.get() != null)
					timeout.get().cancel(true);
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			{
				setTimeout(timeout, success, msg, time, unit);
			}

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (u.isBot() || m == null || !event.getMessageId().equals(msg.getId()))
					return;

				if (checkEmote(reaction, PREVIOUS)) {
					if (p > 0) {
						p--;
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, NEXT)) {
					if (p < maxP) {
						p++;
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, CANCEL)) {
					clearReactions(m, success);
				}

				setTimeout(timeout, success, m, time, unit);

				if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
					event.getReaction().removeReaction(u).submit();
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages.
	 *
	 * @param msg         The {@link Message} sent which will be paginated.
	 * @param pages       The pages to be shown. The order of the {@link List} will
	 *                    define the order of the pages.
	 * @param canInteract {@link Predicate} to determine whether the {@link User}
	 *                    that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);
		clearReactions(msg);

		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				if (canInteract.test(u)) {
					MessageReaction.ReactionEmote reaction = event.getReactionEmote();
					if (u.isBot() || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					if (checkEmote(reaction, PREVIOUS)) {
						if (p > 0) {
							p--;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, NEXT)) {
						if (p < maxP) {
							p++;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
					}

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages. You can specify
	 * how long the listener will stay active before shutting down itself after a
	 * no-activity interval.
	 *
	 * @param msg         The {@link Message} sent which will be paginated.
	 * @param pages       The pages to be shown. The order of the {@link List} will
	 *                    define the order of the pages.
	 * @param time        The time before the listener automatically stop listening
	 *                    for further events (recommended: 60).
	 * @param unit        The time's {@link TimeUnit} (recommended:
	 *                    {@link TimeUnit#SECONDS}).
	 * @param canInteract {@link Predicate} to determine whether the {@link User}
	 *                    that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, int time, @Nonnull TimeUnit unit, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);
		clearReactions(msg);

		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private final Consumer<Void> success = s -> {
				if (timeout.get() != null)
					timeout.get().cancel(true);
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			{
				setTimeout(timeout, success, msg, time, unit);
			}

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}
				if (canInteract.test(u)) {
					MessageReaction.ReactionEmote reaction = event.getReactionEmote();
					if (u.isBot() || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					if (checkEmote(reaction, PREVIOUS)) {
						if (p > 0) {
							p--;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, NEXT)) {
						if (p < maxP) {
							p++;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
					}

					setTimeout(timeout, success, m, time, unit);

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages.
	 *
	 * @param msg         The {@link Message} sent which will be paginated.
	 * @param pages       The pages to be shown. The order of the {@link List} will
	 *                    define the order of the pages.
	 * @param fastForward Whether the {@link Emote#GOTO_FIRST} and {@link Emote#GOTO_LAST} buttons should be shown.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, boolean fastForward) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);
		clearReactions(msg);

		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_FIRST)).submit();
		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_LAST)).submit();

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};


			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (u.isBot() || m == null || !event.getMessageId().equals(msg.getId()))
					return;

				if (checkEmote(reaction, PREVIOUS)) {
					if (p > 0) {
						p--;
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, NEXT)) {
					if (p < maxP) {
						p++;
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, GOTO_FIRST)) {
					if (p > 0) {
						p = 0;
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, GOTO_LAST)) {
					if (p < maxP) {
						p = maxP;
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, CANCEL)) {
					clearReactions(m, success);
				}

				if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
					event.getReaction().removeReaction(u).submit();
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages. You can specify
	 * how long the listener will stay active before shutting down itself after a
	 * no-activity interval.
	 *
	 * @param msg         The {@link Message} sent which will be paginated.
	 * @param pages       The pages to be shown. The order of the {@link List} will
	 *                    define the order of the pages.
	 * @param time        The time before the listener automatically stop listening
	 *                    for further events (recommended: 60).
	 * @param unit        The time's {@link TimeUnit} (recommended:
	 *                    {@link TimeUnit#SECONDS}).
	 * @param fastForward Whether the {@link Emote#GOTO_FIRST} and {@link Emote#GOTO_LAST} buttons should be shown.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, int time, @Nonnull TimeUnit unit, boolean fastForward) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);
		clearReactions(msg);

		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_FIRST)).submit();
		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_LAST)).submit();

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private final Consumer<Void> success = s -> {
				if (timeout.get() != null)
					timeout.get().cancel(true);
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			{
				setTimeout(timeout, success, msg, time, unit);
			}

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (u.isBot() || m == null || !event.getMessageId().equals(msg.getId()))
					return;

				if (checkEmote(reaction, PREVIOUS)) {
					if (p > 0) {
						p--;
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, NEXT)) {
					if (p < maxP) {
						p++;
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, GOTO_FIRST)) {
					if (p > 0) {
						p = 0;
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, GOTO_LAST)) {
					if (p < maxP) {
						p = maxP;
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, CANCEL)) {
					clearReactions(m, success);
				}

				setTimeout(timeout, success, m, time, unit);

				if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
					event.getReaction().removeReaction(u).submit();
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages.
	 *
	 * @param msg         The {@link Message} sent which will be paginated.
	 * @param pages       The pages to be shown. The order of the {@link List} will
	 *                    define the order of the pages.
	 * @param fastForward Whether the {@link Emote#GOTO_FIRST} and {@link Emote#GOTO_LAST} buttons should be shown.
	 * @param canInteract {@link Predicate} to determine whether the {@link User}
	 *                    that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, boolean fastForward, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);
		clearReactions(msg);

		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_FIRST)).submit();
		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_LAST)).submit();

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				if (canInteract.test(u)) {
					MessageReaction.ReactionEmote reaction = event.getReactionEmote();
					if (u.isBot() || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					if (checkEmote(reaction, PREVIOUS)) {
						if (p > 0) {
							p--;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, NEXT)) {
						if (p < maxP) {
							p++;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, GOTO_FIRST)) {
						if (p > 0) {
							p = 0;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, GOTO_LAST)) {
						if (p < maxP) {
							p = maxP;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
					}

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages. You can specify
	 * how long the listener will stay active before shutting down itself after a
	 * no-activity interval.
	 *
	 * @param msg         The {@link Message} sent which will be paginated.
	 * @param pages       The pages to be shown. The order of the {@link List} will
	 *                    define the order of the pages.
	 * @param time        The time before the listener automatically stop listening
	 *                    for further events (recommended: 60).
	 * @param unit        The time's {@link TimeUnit} (recommended:
	 *                    {@link TimeUnit#SECONDS}).
	 * @param fastForward Whether the {@link Emote#GOTO_FIRST} and {@link Emote#GOTO_LAST} buttons should be shown.
	 * @param canInteract {@link Predicate} to determine whether the {@link User}
	 *                    that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, int time, @Nonnull TimeUnit unit, boolean fastForward, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);
		clearReactions(msg);

		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_FIRST)).submit();
		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_LAST)).submit();

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private final Consumer<Void> success = s -> {
				if (timeout.get() != null)
					timeout.get().cancel(true);
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			{
				setTimeout(timeout, success, msg, time, unit);
			}

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				if (canInteract.test(u)) {
					MessageReaction.ReactionEmote reaction = event.getReactionEmote();
					if (u.isBot() || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					if (checkEmote(reaction, PREVIOUS)) {
						if (p > 0) {
							p--;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, NEXT)) {
						if (p < maxP) {
							p++;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, GOTO_FIRST)) {
						if (p > 0) {
							p = 0;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, GOTO_LAST)) {
						if (p < maxP) {
							p = maxP;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
					}

					setTimeout(timeout, success, m, time, unit);

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages.
	 *
	 * @param msg        The {@link Message} sent which will be paginated.
	 * @param pages      The pages to be shown. The order of the {@link List} will
	 *                   define the order of the pages.
	 * @param skipAmount The amount of pages to be skipped when clicking {@link Emote#SKIP_BACKWARD}
	 *                   and {@link Emote#SKIP_FORWARD} buttons.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, int skipAmount) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);
		clearReactions(msg);

		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_BACKWARD)).submit();
		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_FORWARD)).submit();

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (u.isBot() || m == null || !event.getMessageId().equals(msg.getId()))
					return;

				if (checkEmote(reaction, PREVIOUS)) {
					if (p > 0) {
						p--;
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, NEXT)) {
					if (p < maxP) {
						p++;
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, SKIP_BACKWARD)) {
					if (p > 0) {
						p -= (p - skipAmount < 0 ? p : skipAmount);
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, SKIP_FORWARD)) {
					if (p < maxP) {
						p += (p + skipAmount > maxP ? maxP - p : skipAmount);
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, CANCEL)) {
					clearReactions(m, success);
				}

				if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
					event.getReaction().removeReaction(u).submit();
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages. You can specify
	 * how long the listener will stay active before shutting down itself after a
	 * no-activity interval.
	 *
	 * @param msg        The {@link Message} sent which will be paginated.
	 * @param pages      The pages to be shown. The order of the {@link List} will
	 *                   define the order of the pages.
	 * @param time       The time before the listener automatically stop listening
	 *                   for further events (recommended: 60).
	 * @param unit       The time's {@link TimeUnit} (recommended:
	 *                   {@link TimeUnit#SECONDS}).
	 * @param skipAmount The amount of pages to be skipped when clicking {@link Emote#SKIP_BACKWARD}
	 *                   and {@link Emote#SKIP_FORWARD} buttons.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, int time, @Nonnull TimeUnit unit, int skipAmount) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);
		clearReactions(msg);

		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_BACKWARD)).submit();
		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_FORWARD)).submit();

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private final Consumer<Void> success = s -> {
				if (timeout.get() != null)
					timeout.get().cancel(true);
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			{
				setTimeout(timeout, success, msg, time, unit);
			}

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (u.isBot() || m == null || !event.getMessageId().equals(msg.getId()))
					return;

				if (checkEmote(reaction, PREVIOUS)) {
					if (p > 0) {
						p--;
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, NEXT)) {
					if (p < maxP) {
						p++;
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, SKIP_BACKWARD)) {
					if (p > 0) {
						p -= (p - skipAmount < 0 ? p : skipAmount);
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, SKIP_FORWARD)) {
					if (p < maxP) {
						p += (p + skipAmount > maxP ? maxP - p : skipAmount);
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, CANCEL)) {
					clearReactions(m, success);
				}

				setTimeout(timeout, success, m, time, unit);

				if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
					event.getReaction().removeReaction(u).submit();
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages.
	 *
	 * @param msg         The {@link Message} sent which will be paginated.
	 * @param pages       The pages to be shown. The order of the {@link List} will
	 *                    define the order of the pages.
	 * @param skipAmount  The amount of pages to be skipped when clicking {@link Emote#SKIP_BACKWARD}
	 *                    and {@link Emote#SKIP_FORWARD} buttons.
	 * @param canInteract {@link Predicate} to determine whether the {@link User}
	 *                    that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, int skipAmount, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);
		clearReactions(msg);

		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_BACKWARD)).submit();
		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_FORWARD)).submit();

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				if (canInteract.test(u)) {
					MessageReaction.ReactionEmote reaction = event.getReactionEmote();
					if (u.isBot() || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					if (checkEmote(reaction, PREVIOUS)) {
						if (p > 0) {
							p--;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, NEXT)) {
						if (p < maxP) {
							p++;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, SKIP_BACKWARD)) {
						if (p > 0) {
							p -= (p - skipAmount < 0 ? p : skipAmount);
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, SKIP_FORWARD)) {
						if (p < maxP) {
							p += (p + skipAmount > maxP ? maxP - p : skipAmount);
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
					}

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages. You can specify
	 * how long the listener will stay active before shutting down itself after a
	 * no-activity interval.
	 *
	 * @param msg         The {@link Message} sent which will be paginated.
	 * @param pages       The pages to be shown. The order of the {@link List} will
	 *                    define the order of the pages.
	 * @param time        The time before the listener automatically stop listening
	 *                    for further events (recommended: 60).
	 * @param unit        The time's {@link TimeUnit} (recommended:
	 *                    {@link TimeUnit#SECONDS}).
	 * @param skipAmount  The amount of pages to be skipped when clicking {@link Emote#SKIP_BACKWARD}
	 *                    and {@link Emote#SKIP_FORWARD} buttons.
	 * @param canInteract {@link Predicate} to determine whether the {@link User}
	 *                    that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, int time, @Nonnull TimeUnit unit, int skipAmount, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);
		clearReactions(msg);

		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_BACKWARD)).submit();
		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_FORWARD)).submit();

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private final Consumer<Void> success = s -> {
				if (timeout.get() != null)
					timeout.get().cancel(true);
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			{
				setTimeout(timeout, success, msg, time, unit);
			}

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				if (canInteract.test(u)) {
					MessageReaction.ReactionEmote reaction = event.getReactionEmote();
					if (u.isBot() || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					if (checkEmote(reaction, PREVIOUS)) {
						if (p > 0) {
							p--;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, NEXT)) {
						if (p < maxP) {
							p++;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, SKIP_BACKWARD)) {
						if (p > 0) {
							p -= (p - skipAmount < 0 ? p : skipAmount);
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, SKIP_FORWARD)) {
						if (p < maxP) {
							p += (p + skipAmount > maxP ? maxP - p : skipAmount);
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
					}

					setTimeout(timeout, success, m, time, unit);

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages.
	 *
	 * @param msg         The {@link Message} sent which will be paginated.
	 * @param pages       The pages to be shown. The order of the {@link List} will
	 *                    define the order of the pages.
	 * @param skipAmount  The amount of pages to be skipped when clicking {@link Emote#SKIP_BACKWARD}
	 *                    and {@link Emote#SKIP_FORWARD} buttons.
	 * @param fastForward Whether the {@link Emote#GOTO_FIRST} and {@link Emote#GOTO_LAST} buttons should be shown.
	 * @param canInteract {@link Predicate} to determine whether the {@link User}
	 *                    that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, int skipAmount, boolean fastForward, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);
		clearReactions(msg);

		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_FIRST)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_BACKWARD)).submit();
		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_FORWARD)).submit();
		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_LAST)).submit();

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				if (canInteract.test(u)) {
					MessageReaction.ReactionEmote reaction = event.getReactionEmote();
					if (u.isBot() || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					if (checkEmote(reaction, PREVIOUS)) {
						if (p > 0) {
							p--;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, NEXT)) {
						if (p < maxP) {
							p++;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, SKIP_BACKWARD)) {
						if (p > 0) {
							p -= (p - skipAmount < 0 ? p : skipAmount);
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, SKIP_FORWARD)) {
						if (p < maxP) {
							p += (p + skipAmount > maxP ? maxP - p : skipAmount);
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, GOTO_FIRST)) {
						if (p > 0) {
							p = 0;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, GOTO_LAST)) {
						if (p < maxP) {
							p = maxP;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
					}

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages. You can specify
	 * how long the listener will stay active before shutting down itself after a
	 * no-activity interval.
	 *
	 * @param msg         The {@link Message} sent which will be paginated.
	 * @param pages       The pages to be shown. The order of the {@link List} will
	 *                    define the order of the pages.
	 * @param time        The time before the listener automatically stop listening
	 *                    for further events (recommended: 60).
	 * @param unit        The time's {@link TimeUnit} (recommended:
	 *                    {@link TimeUnit#SECONDS}).
	 * @param skipAmount  The amount of pages to be skipped when clicking {@link Emote#SKIP_BACKWARD}
	 *                    and {@link Emote#SKIP_FORWARD} buttons.
	 * @param fastForward Whether the {@link Emote#GOTO_FIRST} and {@link Emote#GOTO_LAST} buttons should be shown.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, int time, @Nonnull TimeUnit unit, int skipAmount, boolean fastForward) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);
		clearReactions(msg);

		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_FIRST)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_BACKWARD)).submit();
		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_FORWARD)).submit();
		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_LAST)).submit();

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
			private final Consumer<Void> success = s -> {
				if (timeout.get() != null)
					timeout.get().cancel(true);
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			{
				setTimeout(timeout, success, msg, time, unit);
			}

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (u.isBot() || m == null || !event.getMessageId().equals(msg.getId()))
					return;

				if (checkEmote(reaction, PREVIOUS)) {
					if (p > 0) {
						p--;
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, NEXT)) {
					if (p < maxP) {
						p++;
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, SKIP_BACKWARD)) {
					if (p > 0) {
						p -= (p - skipAmount < 0 ? p : skipAmount);
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, SKIP_FORWARD)) {
					if (p < maxP) {
						p += (p + skipAmount > maxP ? maxP - p : skipAmount);
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, GOTO_FIRST)) {
					if (p > 0) {
						p = 0;
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, GOTO_LAST)) {
					if (p < maxP) {
						p = maxP;
						Page pg = pgs.get(p);

						updatePage(m, pg);
					}
				} else if (checkEmote(reaction, CANCEL)) {
					clearReactions(m, success);
				}

				setTimeout(timeout, success, m, time, unit);

				if (event.isFromGuild() && (paginator == null || paginator.isRemoveOnReact())) {
					event.getReaction().removeReaction(u).submit();
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages. You can specify
	 * how long the listener will stay active before shutting down itself after a
	 * no-activity interval.
	 *
	 * @param msg         The {@link Message} sent which will be paginated.
	 * @param pages       The pages to be shown. The order of the {@link List} will
	 *                    define the order of the pages.
	 * @param time        The time before the listener automatically stop listening
	 *                    for further events (recommended: 60).
	 * @param unit        The time's {@link TimeUnit} (recommended:
	 *                    {@link TimeUnit#SECONDS}).
	 * @param skipAmount  The amount of pages to be skipped when clicking {@link Emote#SKIP_BACKWARD}
	 *                    and {@link Emote#SKIP_FORWARD} buttons.
	 * @param fastForward Whether the {@link Emote#GOTO_FIRST} and {@link Emote#GOTO_LAST} buttons should be shown.
	 * @param canInteract {@link Predicate} to determine whether the {@link User}
	 *                    that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginate(@Nonnull Message msg, @Nonnull List<Page> pages, int time, @Nonnull TimeUnit unit, int skipAmount, boolean fastForward, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException {
		if (!isActivated()) throw new InvalidStateException();
		List<Page> pgs = Collections.unmodifiableList(pages);
		clearReactions(msg);

		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_FIRST)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_BACKWARD)).submit();
		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_FORWARD)).submit();
		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_LAST)).submit();

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final int maxP = pgs.size() - 1;
			private int p = 0;
			private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
			private final Consumer<Void> success = s -> {
				if (timeout.get() != null)
					timeout.get().cancel(true);
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			{
				setTimeout(timeout, success, msg, time, unit);
			}

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				if (canInteract.test(u)) {
					MessageReaction.ReactionEmote reaction = event.getReactionEmote();
					if (u.isBot() || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					if (checkEmote(reaction, PREVIOUS)) {
						if (p > 0) {
							p--;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, NEXT)) {
						if (p < maxP) {
							p++;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, SKIP_BACKWARD)) {
						if (p > 0) {
							p -= (p - skipAmount < 0 ? p : skipAmount);
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, SKIP_FORWARD)) {
						if (p < maxP) {
							p += (p + skipAmount > maxP ? maxP - p : skipAmount);
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, GOTO_FIRST)) {
						if (p > 0) {
							p = 0;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, GOTO_LAST)) {
						if (p < maxP) {
							p = maxP;
							Page pg = pgs.get(p);

							updatePage(m, pg);
						}
					} else if (checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
					}

					setTimeout(timeout, success, m, time, unit);

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * Adds menu-like buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will browse through a given {@link Map} of pages. You may only specify
	 * one {@link Page} per button, adding another button with an existing unicode
	 * will overwrite the current button's {@link Page}.
	 *
	 * @param msg        The {@link Message} sent which will be categorized.
	 * @param categories The categories to be shown. The categories are defined by
	 *                   a {@link Map} containing emoji unicodes or emote ids as keys and
	 *                   {@link Pages} as values.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 * @throws InvalidEmoteException           Thrown if one of the custom emotes' ID is invalid or not from a guild your bot is member of.
	 */
	public static void categorize(@Nonnull Message msg, @Nonnull Map<String, Page> categories) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		Map<String, Page> cats = Collections.unmodifiableMap(categories);
		clearReactions(msg);

		for (String k : cats.keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private String currCat = "";
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (u.isBot() || reaction.getName().equals(currCat) || m == null || !event.getMessageId().equals(msg.getId()))
					return;

				if (checkEmote(reaction, CANCEL)) {
					clearReactions(m, success);
					return;
				}

				String code = reaction.isEmoji() ? reaction.getName() : reaction.getId();
				Page pg = cats.get(code);
				if (pg != null) {
					updatePage(m, pg);
					currCat = code;
				}

				if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
					event.getReaction().removeReaction(u).submit();
				}
			}
		});
	}

	/**
	 * Adds menu-like buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will browse through a given {@link Map} of pages. You may only specify
	 * one {@link Page} per button, adding another button with an existing unicode
	 * will overwrite the current button's {@link Page}. You can specify how long
	 * the listener will stay active before shutting down itself after a no-activity
	 * interval.
	 *
	 * @param msg        The {@link Message} sent which will be categorized.
	 * @param categories The categories to be shown. The categories are defined by a
	 *                   {@link Map} containing emoji unicodes or emote ids as keys and
	 *                   {@link Pages} as values.
	 * @param time       The time before the listener automatically stop listening
	 *                   for further events (recommended: 60).
	 * @param unit       The time's {@link TimeUnit} (recommended:
	 *                   {@link TimeUnit#SECONDS}).
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 * @throws InvalidEmoteException           Thrown if one of the custom emotes' ID is invalid or not from a guild your bot is member of.
	 */
	public static void categorize(@Nonnull Message msg, @Nonnull Map<String, Page> categories, int time, @Nonnull TimeUnit unit) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		Map<String, Page> cats = Collections.unmodifiableMap(categories);
		clearReactions(msg);

		for (String k : cats.keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private String currCat = "";
			private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
			private final Consumer<Void> success = s -> {
				if (timeout.get() != null)
					timeout.get().cancel(true);
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			{
				setTimeout(timeout, success, msg, time, unit);
			}

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (u.isBot() || reaction.getName().equals(currCat) || m == null || !event.getMessageId().equals(msg.getId()))
					return;

				if (checkEmote(reaction, CANCEL)) {
					clearReactions(m, success);
					return;
				}

				setTimeout(timeout, success, m, time, unit);

				String code = reaction.isEmoji() ? reaction.getName() : reaction.getId();
				Page pg = cats.get(code);
				if (pg != null) {
					updatePage(m, pg);
					currCat = code;
				}

				if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
					event.getReaction().removeReaction(u).submit();
				}
			}
		});
	}

	/**
	 * Adds menu-like buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will browse through a given {@link Map} of pages. You may only specify
	 * one {@link Page} per button, adding another button with an existing unicode
	 * will overwrite the current button's {@link Page}.
	 *
	 * @param msg         The {@link Message} sent which will be categorized.
	 * @param categories  The categories to be shown. The categories are defined by
	 *                    a {@link Map} containing emoji unicodes or emote ids as keys and
	 *                    {@link Pages} as values.
	 * @param canInteract {@link Predicate} to determine whether the {@link User}
	 *                    that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 * @throws InvalidEmoteException           Thrown if one of the custom emotes' ID is invalid or not from a guild your bot is member of.
	 */
	public static void categorize(@Nonnull Message msg, @Nonnull Map<String, Page> categories, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		Map<String, Page> cats = Collections.unmodifiableMap(categories);
		clearReactions(msg);

		for (String k : cats.keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private String currCat = "";
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (canInteract.test(u)) {
					if (u.isBot() || reaction.getName().equals(currCat) || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					if (checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
						return;
					}

					String code = reaction.isEmoji() ? reaction.getName() : reaction.getId();
					Page pg = cats.get(code);
					if (pg != null) {
						updatePage(m, pg);
						currCat = code;
					}

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * Adds menu-like buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will browse through a given {@link Map} of pages. You may only specify
	 * one {@link Page} per button, adding another button with an existing unicode
	 * will overwrite the current button's {@link Page}. You can specify how long
	 * the listener will stay active before shutting down itself after a no-activity
	 * interval.
	 *
	 * @param msg         The {@link Message} sent which will be categorized.
	 * @param categories  The categories to be shown. The categories are defined by
	 *                    a {@link Map} containing emoji unicodes or emote ids as keys and
	 *                    {@link Pages} as values.
	 * @param time        The time before the listener automatically stop listening
	 *                    for further events (recommended: 60).
	 * @param unit        The time's {@link TimeUnit} (recommended:
	 *                    {@link TimeUnit#SECONDS}).
	 * @param canInteract {@link Predicate} to determine whether the {@link User}
	 *                    that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 * @throws InvalidEmoteException           Thrown if one of the custom emotes' ID is invalid or not from a guild your bot is member of.
	 */
	public static void categorize(@Nonnull Message msg, @Nonnull Map<String, Page> categories, int time, @Nonnull TimeUnit unit, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		Map<String, Page> cats = Collections.unmodifiableMap(categories);
		clearReactions(msg);

		for (String k : cats.keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private String currCat = "";
			private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
			private final Consumer<Void> success = s -> {
				if (timeout.get() != null)
					timeout.get().cancel(true);
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			{
				setTimeout(timeout, success, msg, time, unit);
			}

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (canInteract.test(u)) {
					if (u.isBot() || reaction.getName().equals(currCat) || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					if (checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
						return;
					}

					setTimeout(timeout, success, m, time, unit);

					String code = reaction.isEmoji() ? reaction.getName() : reaction.getId();
					Page pg = cats.get(code);
					if (pg != null) {
						updatePage(m, pg);
						currCat = code;
					}

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * Adds buttons to the specified {@link Message}/{@link MessageEmbed}, with each
	 * executing a specific task on click. You may only specify one {@link Runnable}
	 * per button, adding another button with an existing unicode will overwrite the
	 * current button's {@link Runnable}.
	 *
	 * @param msg              The {@link Message} sent which will be buttoned.
	 * @param buttons          The buttons to be shown. The buttons are defined by a
	 *                         {@link Map} containing emoji unicodes or emote ids as keys and
	 *                         {@link ThrowingBiConsumer}&lt;{@link Member}, {@link Message}&gt;.
	 *                         containing desired behavior as value.
	 * @param showCancelButton Should the {@link Emote#CANCEL} button be created automatically?
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 * @throws InvalidEmoteException           Thrown if one of the custom emotes' ID is invalid or not from a guild your bot is member of.
	 */
	public static void buttonize(@Nonnull Message msg, @Nonnull Map<String, ThrowingBiConsumer<Member, Message>> buttons, boolean showCancelButton) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		Map<String, ThrowingBiConsumer<Member, Message>> btns = Collections.unmodifiableMap(buttons);
		clearReactions(msg);

		for (String k : btns.keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}
		if (!btns.containsKey(paginator.getEmote(CANCEL)) && showCancelButton)
			msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}
				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (u.isBot() || m == null || !event.getMessageId().equals(msg.getId()))
					return;

				if ((!btns.containsKey(paginator.getEmote(CANCEL)) && showCancelButton) && checkEmote(reaction, CANCEL)) {
					clearReactions(m, success);
					return;
				}

				if (reaction.isEmoji())
					btns.get(reaction.getName()).accept(event.getMember(), m);
				else
					btns.get(reaction.getId()).accept(event.getMember(), m);

				if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
					event.getReaction().removeReaction(u).submit();
				}
			}
		});
	}

	/**
	 * Adds buttons to the specified {@link Message}/{@link MessageEmbed}, with each
	 * executing a specific task on click. You may only specify one {@link Runnable}
	 * per button, adding another button with an existing unicode will overwrite the
	 * current button's {@link Runnable}. You can specify the time in which the
	 * listener will automatically stop itself after a no-activity interval.
	 *
	 * @param msg              The {@link Message} sent which will be buttoned.
	 * @param buttons          The buttons to be shown. The buttons are defined by a
	 *                         Map containing emoji unicodes or emote ids as keys and
	 *                         {@link ThrowingBiConsumer}&lt;{@link Member}, {@link Message}&gt;
	 *                         containing desired behavior as value.
	 * @param showCancelButton Should the {@link Emote#CANCEL} button be created automatically?
	 * @param time             The time before the listener automatically stop
	 *                         listening for further events (recommended: 60).
	 * @param unit             The time's {@link TimeUnit} (recommended:
	 *                         {@link TimeUnit#SECONDS}).
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 * @throws InvalidEmoteException           Thrown if one of the custom emotes' ID is invalid or not from a guild your bot is member of.
	 */
	public static void buttonize(@Nonnull Message msg, @Nonnull Map<String, ThrowingBiConsumer<Member, Message>> buttons, boolean showCancelButton, int time, @Nonnull TimeUnit unit) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		Map<String, ThrowingBiConsumer<Member, Message>> btns = Collections.unmodifiableMap(buttons);
		clearReactions(msg);

		for (String k : btns.keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}
		if (!btns.containsKey(paginator.getEmote(CANCEL)) && showCancelButton)
			msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
			private final Consumer<Void> success = s -> {
				if (timeout.get() != null)
					timeout.get().cancel(true);
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			{
				setTimeout(timeout, success, msg, time, unit);
			}

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}
				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (u.isBot() || m == null || !event.getMessageId().equals(msg.getId()))
					return;

				if ((!btns.containsKey(paginator.getEmote(CANCEL)) && showCancelButton) && checkEmote(reaction, CANCEL)) {
					clearReactions(m, success);
					return;
				}

				if (reaction.isEmoji())
					btns.get(reaction.getName()).accept(event.getMember(), m);
				else
					btns.get(reaction.getId()).accept(event.getMember(), m);

				setTimeout(timeout, success, m, time, unit);

				if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
					event.getReaction().removeReaction(u).submit();
				}
			}
		});
	}

	/**
	 * Adds buttons to the specified {@link Message}/{@link MessageEmbed}, with each
	 * executing a specific task on click. You must only specify one
	 * {@link Runnable} per button, adding another button with an existing unicode
	 * will overwrite the current button's {@link Runnable}.
	 *
	 * @param msg              The {@link Message} sent which will be buttoned.
	 * @param buttons          The buttons to be shown. The buttons are defined by a
	 *                         Map containing emoji unicodes or emote ids as keys and
	 *                         {@link ThrowingBiConsumer}&lt;{@link Member}, {@link Message}&gt;
	 *                         containing desired behavior as value.
	 * @param showCancelButton Should the {@link Emote#CANCEL} button be created automatically?
	 * @param canInteract      {@link Predicate} to determine whether the
	 *                         {@link User} that pressed the button can interact
	 *                         with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 * @throws InvalidEmoteException           Thrown if one of the custom emotes' ID is invalid or not from a guild your bot is member of.
	 */
	public static void buttonize(@Nonnull Message msg, @Nonnull Map<String, ThrowingBiConsumer<Member, Message>> buttons, boolean showCancelButton, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		Map<String, ThrowingBiConsumer<Member, Message>> btns = Collections.unmodifiableMap(buttons);
		clearReactions(msg);

		for (String k : btns.keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}
		if (!btns.containsKey(paginator.getEmote(CANCEL)) && showCancelButton)
			msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}
				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (canInteract.test(u)) {
					if (u.isBot() || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					if ((!btns.containsKey(paginator.getEmote(CANCEL)) && showCancelButton) && checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
						return;
					}

					if (reaction.isEmoji())
						btns.get(reaction.getName()).accept(event.getMember(), m);
					else
						btns.get(reaction.getId()).accept(event.getMember(), m);

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * Adds buttons to the specified {@link Message}/{@link MessageEmbed}, with each
	 * executing a specific task on click. You must only specify one
	 * {@link Runnable} per button, adding another button with an existing unicode
	 * will overwrite the current button's {@link Runnable}. You can specify the
	 * time in which the listener will automatically stop itself after a no-activity
	 * interval.
	 *
	 * @param msg              The {@link Message} sent which will be buttoned.
	 * @param buttons          The buttons to be shown. The buttons are defined by a
	 *                         Map containing emoji unicodes or emote ids as keys and
	 *                         {@link ThrowingBiConsumer}&lt;{@link Member}, {@link Message}&gt;
	 *                         containing desired behavior as value.
	 * @param showCancelButton Should the {@link Emote#CANCEL} button be created automatically?
	 * @param time             The time before the listener automatically stop
	 *                         listening for further events (recommended: 60).
	 * @param unit             The time's {@link TimeUnit} (recommended:
	 *                         {@link TimeUnit#SECONDS}).
	 * @param canInteract      {@link Predicate} to determine whether the
	 *                         {@link User} that pressed the button can interact
	 *                         with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 * @throws InvalidEmoteException           Thrown if one of the custom emotes' ID is invalid or not from a guild your bot is member of.
	 */
	public static void buttonize(@Nonnull Message msg, @Nonnull Map<String, ThrowingBiConsumer<Member, Message>> buttons, boolean showCancelButton, int time, @Nonnull TimeUnit unit, Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		Map<String, ThrowingBiConsumer<Member, Message>> btns = Collections.unmodifiableMap(buttons);
		clearReactions(msg);

		for (String k : btns.keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}
		if (!btns.containsKey(paginator.getEmote(CANCEL)) && showCancelButton)
			msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
			private final Consumer<Void> success = s -> {
				if (timeout.get() != null)
					timeout.get().cancel(true);
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			{
				setTimeout(timeout, success, msg, time, unit);
			}

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}
				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (canInteract.test(u)) {
					if (u.isBot() || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					if ((!btns.containsKey(paginator.getEmote(CANCEL)) && showCancelButton) && checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
						return;
					}

					if (reaction.isEmoji())
						btns.get(reaction.getName()).accept(event.getMember(), m);
					else
						btns.get(reaction.getId()).accept(event.getMember(), m);

					setTimeout(timeout, success, m, time, unit);

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * Adds buttons to the specified {@link Message}/{@link MessageEmbed}, with each
	 * executing a specific task on click. You may only specify one {@link Runnable}
	 * per button, adding another button with an existing unicode will overwrite the
	 * current button's {@link Runnable}.
	 *
	 * @param msg              The {@link Message} sent which will be buttoned.
	 * @param buttons          The buttons to be shown. The buttons are defined by a
	 *                         Map containing emoji unicodes or emote ids as keys and
	 *                         {@link ThrowingBiConsumer}&lt;{@link Member}, {@link Message}&gt;
	 *                         containing desired behavior as value.
	 * @param showCancelButton Should the {@link Emote#CANCEL} button be created automatically?
	 * @param canInteract      {@link Predicate} to determine whether the
	 *                         {@link User} that pressed the button can interact
	 *                         with it or not.
	 * @param onCancel         Action to be ran after the listener is removed.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void buttonize(@Nonnull Message msg, @Nonnull Map<String, ThrowingBiConsumer<Member, Message>> buttons, boolean showCancelButton, @Nonnull Predicate<User> canInteract, @Nonnull Consumer<Message> onCancel) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		Map<String, ThrowingBiConsumer<Member, Message>> btns = Collections.unmodifiableMap(buttons);
		clearReactions(msg);

		for (String k : btns.keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}
		if (!btns.containsKey(paginator.getEmote(CANCEL)) && showCancelButton)
			msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				onCancel.accept(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}
				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (canInteract.test(u)) {
					if (u.isBot() || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					if ((!btns.containsKey(paginator.getEmote(CANCEL)) && showCancelButton) && checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
						return;
					}

					if (reaction.isEmoji())
						btns.get(reaction.getName()).accept(event.getMember(), m);
					else
						btns.get(reaction.getId()).accept(event.getMember(), m);

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * Adds buttons to the specified {@link Message}/{@link MessageEmbed}, with each
	 * executing a specific task on click. You may only specify one {@link Runnable}
	 * per button, adding another button with an existing unicode will overwrite the
	 * current button's {@link Runnable}. You can specify the time in which the
	 * listener will automatically stop itself after a no-activity interval.
	 *
	 * @param msg              The {@link Message} sent which will be buttoned.
	 * @param buttons          The buttons to be shown. The buttons are defined by a
	 *                         Map containing emoji unicodes or emote ids as keys and
	 *                         {@link ThrowingBiConsumer}&lt;{@link Member}, {@link Message}&gt;
	 *                         containing desired behavior as value.
	 * @param showCancelButton Should the {@link Emote#CANCEL} button be created automatically?
	 * @param time             The time before the listener automatically stop
	 *                         listening for further events (recommended: 60).
	 * @param unit             The time's {@link TimeUnit} (recommended:
	 *                         {@link TimeUnit#SECONDS}).
	 * @param canInteract      {@link Predicate} to determine whether the
	 *                         {@link User} that pressed the button can interact
	 *                         with it or not.
	 * @param onCancel         Action to be ran after the listener is removed.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 * @throws InvalidEmoteException           Thrown if one of the custom emotes' ID is invalid or not from a guild your bot is member of.
	 */
	public static void buttonize(@Nonnull Message msg, @Nonnull Map<String, ThrowingBiConsumer<Member, Message>> buttons, boolean showCancelButton, int time, @Nonnull TimeUnit unit, @Nonnull Predicate<User> canInteract, @Nonnull Consumer<Message> onCancel) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		Map<String, ThrowingBiConsumer<Member, Message>> btns = Collections.unmodifiableMap(buttons);
		clearReactions(msg);

		for (String k : btns.keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}
		if (!btns.containsKey(paginator.getEmote(CANCEL)) && showCancelButton)
			msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
			private final Consumer<Void> success = s -> {
				if (timeout.get() != null)
					timeout.get().cancel(true);
				handler.removeEvent(msg);
				onCancel.accept(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			{
				setTimeout(timeout, success, msg, time, unit);
			}

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}
				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (canInteract.test(u)) {
					if (u.isBot() || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					if ((!btns.containsKey(paginator.getEmote(CANCEL)) && showCancelButton) && checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
						return;
					}

					if (reaction.isEmoji())
						btns.get(reaction.getName()).accept(event.getMember(), m);
					else
						btns.get(reaction.getId()).accept(event.getMember(), m);

					setTimeout(timeout, success, m, time, unit);

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages while allowing
	 * menu-like navigation akin to {@link #categorize(Message, Map)}.
	 *
	 * @param msg              The {@link Message} sent which will be paginated.
	 * @param paginocategories The pages to be shown. The order of the {@link List} will define
	 *                         the order of the pages.
	 * @param faces            The pages to be shown as default for each index. The {@link List} must have the
	 *                         same amount of indexes as the category {@link List}, else it'll be ignored (can be null).
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginoCategorize(@Nonnull Message msg, @Nonnull List<Map<String, Page>> paginocategories, @Nullable List<Page> faces) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		List<Map<String, Page>> pgs = Collections.unmodifiableList(paginocategories);
		clearReactions(msg);

		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		for (String k : pgs.get(0).keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private int p = 0;
			private String currCat = "";
			private Map<String, Page> cats = pgs.get(0);
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (u.isBot() || reaction.getName().equals(currCat) || m == null || !event.getMessageId().equals(msg.getId()))
					return;

				boolean update = false;
				if (checkEmote(reaction, PREVIOUS)) {
					if (p > 0) {
						p--;
						update = true;
					}
				} else if (checkEmote(reaction, NEXT)) {
					if (p < pgs.size() - 1) {
						p++;
						update = true;
					}
				} else if (checkEmote(reaction, CANCEL)) {
					clearReactions(m, success);
					return;
				}

				cats = pgs.get(p);
				if (update) {
					if (faces != null && pgs.size() == faces.size()) {
						Page face = faces.get(p);
						if (face != null) updatePage(m, face);
					}
					currCat = "";
					clearReactions(m);
					m.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
					m.addReaction(paginator.getEmotes().get(CANCEL)).submit();
					m.addReaction(paginator.getEmotes().get(NEXT)).submit();

					for (String k : cats.keySet()) {
						if (EmojiUtils.containsEmoji(k))
							m.addReaction(k).submit();
						else {
							net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
							if (e == null) throw new InvalidEmoteException();
							m.addReaction(e).submit();
						}
					}
				}

				String code = reaction.isEmoji() ? reaction.getName() : reaction.getId();
				Page pg = cats.get(code);
				if (pg != null) {
					updatePage(m, pg);
					currCat = code;
				}

				if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
					event.getReaction().removeReaction(u).submit();
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages while allowing
	 * menu-like navigation akin to {@link #categorize(Message, Map)}.
	 *
	 * @param msg              The {@link Message} sent which will be paginated.
	 * @param paginocategories The pages to be shown. The order of the {@link List} will define
	 *                         the order of the pages.
	 * @param faces            The pages to be shown as default for each index. The {@link List} must have the
	 *                         same amount of indexes as the category {@link List}, else it'll be ignored (can be null).
	 * @param time             The time before the listener automatically stop listening for
	 *                         further events (recommended: 60).
	 * @param unit             The time's {@link TimeUnit} (recommended: {@link TimeUnit#SECONDS}).
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginoCategorize(@Nonnull Message msg, @Nonnull List<Map<String, Page>> paginocategories, @Nullable List<Page> faces, int time, @Nonnull TimeUnit unit) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		List<Map<String, Page>> pgs = Collections.unmodifiableList(paginocategories);
		clearReactions(msg);

		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		for (String k : pgs.get(0).keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
			private int p = 0;
			private String currCat = "";
			private Map<String, Page> cats = pgs.get(0);
			private final Consumer<Void> success = s -> {
				if (timeout.get() != null)
					timeout.get().cancel(true);
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			{
				setTimeout(timeout, success, msg, time, unit);
			}

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (u.isBot() || reaction.getName().equals(currCat) || m == null || !event.getMessageId().equals(msg.getId()))
					return;

				boolean update = false;
				if (checkEmote(reaction, PREVIOUS)) {
					if (p > 0) {
						p--;
						update = true;
					}
				} else if (checkEmote(reaction, NEXT)) {
					if (p < pgs.size() - 1) {
						p++;
						update = true;
					}
				} else if (checkEmote(reaction, CANCEL)) {
					clearReactions(m, success);
					return;
				}

				cats = pgs.get(p);
				if (update) {
					if (faces != null && pgs.size() == faces.size()) {
						Page face = faces.get(p);
						if (face != null) updatePage(m, face);
					}
					currCat = "";
					clearReactions(m);
					m.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
					m.addReaction(paginator.getEmotes().get(CANCEL)).submit();
					m.addReaction(paginator.getEmotes().get(NEXT)).submit();

					for (String k : cats.keySet()) {
						if (EmojiUtils.containsEmoji(k))
							m.addReaction(k).submit();
						else {
							net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
							if (e == null) throw new InvalidEmoteException();
							m.addReaction(e).submit();
						}
					}
				}

				String code = reaction.isEmoji() ? reaction.getName() : reaction.getId();
				Page pg = cats.get(code);
				if (pg != null) {
					updatePage(m, pg);
					currCat = code;
				}

				setTimeout(timeout, success, m, time, unit);

				if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
					event.getReaction().removeReaction(u).submit();
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages while allowing
	 * menu-like navigation akin to {@link #categorize(Message, Map)}.
	 *
	 * @param msg              The {@link Message} sent which will be paginated.
	 * @param paginocategories The pages to be shown. The order of the {@link List} will define
	 *                         the order of the pages.
	 * @param faces            The pages to be shown as default for each index. The {@link List} must have the
	 *                         same amount of indexes as the category {@link List}, else it'll be ignored (can be null).
	 * @param canInteract      {@link Predicate} to determine whether the {@link User}
	 *                         that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginoCategorize(@Nonnull Message msg, @Nonnull List<Map<String, Page>> paginocategories, @Nullable List<Page> faces, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		List<Map<String, Page>> pgs = Collections.unmodifiableList(paginocategories);
		clearReactions(msg);

		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		for (String k : pgs.get(0).keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private int p = 0;
			private String currCat = "";
			private Map<String, Page> cats = pgs.get(0);
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				if (canInteract.test(u)) {
					MessageReaction.ReactionEmote reaction = event.getReactionEmote();
					if (u.isBot() || reaction.getName().equals(currCat) || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					boolean update = false;
					if (checkEmote(reaction, PREVIOUS)) {
						if (p > 0) {
							p--;
							update = true;
						}
					} else if (checkEmote(reaction, NEXT)) {
						if (p < pgs.size() - 1) {
							p++;
							update = true;
						}
					} else if (checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
						return;
					}

					cats = pgs.get(p);
					if (update) {
						if (faces != null && pgs.size() == faces.size()) {
							Page face = faces.get(p);
							if (face != null) updatePage(m, face);
						}
						currCat = "";
						clearReactions(m);
						m.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
						m.addReaction(paginator.getEmotes().get(CANCEL)).submit();
						m.addReaction(paginator.getEmotes().get(NEXT)).submit();

						for (String k : cats.keySet()) {
							if (EmojiUtils.containsEmoji(k))
								m.addReaction(k).submit();
							else {
								net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
								if (e == null) throw new InvalidEmoteException();
								m.addReaction(e).submit();
							}
						}
					}

					String code = reaction.isEmoji() ? reaction.getName() : reaction.getId();
					Page pg = cats.get(code);
					if (pg != null) {
						updatePage(m, pg);
						currCat = code;
					}

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages while allowing
	 * menu-like navigation akin to {@link #categorize(Message, Map)}.
	 *
	 * @param msg              The {@link Message} sent which will be paginated.
	 * @param paginocategories The pages to be shown. The order of the {@link List} will define
	 *                         the order of the pages.
	 * @param faces            The pages to be shown as default for each index. The {@link List} must have the
	 *                         same amount of indexes as the category {@link List}, else it'll be ignored (can be null).
	 * @param time             The time before the listener automatically stop listening
	 *                         for further events (recommended: 60).
	 * @param unit             The time's {@link TimeUnit} (recommended:
	 *                         {@link TimeUnit#SECONDS}).
	 * @param canInteract      {@link Predicate} to determine whether the {@link User}
	 *                         that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginoCategorize(@Nonnull Message msg, @Nonnull List<Map<String, Page>> paginocategories, @Nullable List<Page> faces, int time, @Nonnull TimeUnit unit, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		List<Map<String, Page>> pgs = Collections.unmodifiableList(paginocategories);
		clearReactions(msg);

		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		for (String k : pgs.get(0).keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
			private int p = 0;
			private String currCat = "";
			private Map<String, Page> cats = pgs.get(0);
			private final Consumer<Void> success = s -> {
				if (timeout.get() != null)
					timeout.get().cancel(true);
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			{
				setTimeout(timeout, success, msg, time, unit);
			}

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				if (canInteract.test(u)) {
					MessageReaction.ReactionEmote reaction = event.getReactionEmote();
					if (u.isBot() || reaction.getName().equals(currCat) || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					boolean update = false;
					if (checkEmote(reaction, PREVIOUS)) {
						if (p > 0) {
							p--;
							update = true;
						}
					} else if (checkEmote(reaction, NEXT)) {
						if (p < pgs.size() - 1) {
							p++;
							update = true;
						}
					} else if (checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
						return;
					}

					cats = pgs.get(p);
					if (update) {
						if (faces != null && pgs.size() == faces.size()) {
							Page face = faces.get(p);
							if (face != null) updatePage(m, face);
						}
						currCat = "";
						clearReactions(m);
						m.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
						m.addReaction(paginator.getEmotes().get(CANCEL)).submit();
						m.addReaction(paginator.getEmotes().get(NEXT)).submit();

						for (String k : cats.keySet()) {
							if (EmojiUtils.containsEmoji(k))
								m.addReaction(k).submit();
							else {
								net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
								if (e == null) throw new InvalidEmoteException();
								m.addReaction(e).submit();
							}
						}
					}

					String code = reaction.isEmoji() ? reaction.getName() : reaction.getId();
					Page pg = cats.get(code);
					if (pg != null) {
						updatePage(m, pg);
						currCat = code;
					}

					setTimeout(timeout, success, m, time, unit);

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages while allowing
	 * menu-like navigation akin to {@link #categorize(Message, Map)}.
	 *
	 * @param msg              The {@link Message} sent which will be paginated.
	 * @param paginocategories The pages to be shown. The order of the {@link List} will define
	 *                         the order of the pages.
	 * @param faces            The pages to be shown as default for each index. The {@link List} must have the
	 *                         same amount of indexes as the category {@link List}, else it'll be ignored (can be null).
	 * @param fastForward      Whether the {@link Emote#GOTO_FIRST} and {@link Emote#GOTO_LAST} buttons should be shown.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginoCategorize(@Nonnull Message msg, @Nonnull List<Map<String, Page>> paginocategories, @Nullable List<Page> faces, boolean fastForward) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		List<Map<String, Page>> pgs = Collections.unmodifiableList(paginocategories);
		clearReactions(msg);

		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_FIRST)).submit();
		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_LAST)).submit();
		for (String k : pgs.get(0).keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private int p = 0;
			private String currCat = "";
			private Map<String, Page> cats = pgs.get(0);
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (u.isBot() || reaction.getName().equals(currCat) || m == null || !event.getMessageId().equals(msg.getId()))
					return;

				boolean update = false;
				if (checkEmote(reaction, PREVIOUS)) {
					if (p > 0) {
						p--;
						update = true;
					}
				} else if (checkEmote(reaction, NEXT)) {
					if (p < pgs.size() - 1) {
						p++;
						update = true;
					}
				} else if (checkEmote(reaction, GOTO_FIRST)) {
					if (p > 0) {
						p = 0;
						update = true;
					}
				} else if (checkEmote(reaction, GOTO_LAST)) {
					if (p < pgs.size() - 1) {
						p = pgs.size() - 1;
						update = true;
					}
				} else if (checkEmote(reaction, CANCEL)) {
					clearReactions(m, success);
					return;
				}

				cats = pgs.get(p);
				if (update) {
					if (faces != null && pgs.size() == faces.size()) {
						Page face = faces.get(p);
						if (face != null) updatePage(m, face);
					}
					currCat = "";
					clearReactions(m);
					m.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
					m.addReaction(paginator.getEmotes().get(CANCEL)).submit();
					m.addReaction(paginator.getEmotes().get(NEXT)).submit();

					for (String k : cats.keySet()) {
						if (EmojiUtils.containsEmoji(k))
							m.addReaction(k).submit();
						else {
							net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
							if (e == null) throw new InvalidEmoteException();
							m.addReaction(e).submit();
						}
					}
				}

				String code = reaction.isEmoji() ? reaction.getName() : reaction.getId();
				Page pg = cats.get(code);
				if (pg != null) {
					updatePage(m, pg);
					currCat = code;
				}

				if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
					event.getReaction().removeReaction(u).submit();
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages while allowing
	 * menu-like navigation akin to {@link #categorize(Message, Map)}.
	 *
	 * @param msg              The {@link Message} sent which will be paginated.
	 * @param paginocategories The pages to be shown. The order of the {@link List} will define
	 *                         the order of the pages.
	 * @param faces            The pages to be shown as default for each index. The {@link List} must have the
	 *                         same amount of indexes as the category {@link List}, else it'll be ignored (can be null).
	 * @param time             The time before the listener automatically stop listening
	 *                         for further events (recommended: 60).
	 * @param unit             The time's {@link TimeUnit} (recommended:
	 *                         {@link TimeUnit#SECONDS}).
	 * @param fastForward      Whether the {@link Emote#GOTO_FIRST} and {@link Emote#GOTO_LAST} buttons should be shown.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginoCategorize(@Nonnull Message msg, @Nonnull List<Map<String, Page>> paginocategories, @Nullable List<Page> faces, int time, @Nonnull TimeUnit unit, boolean fastForward) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		List<Map<String, Page>> pgs = Collections.unmodifiableList(paginocategories);
		clearReactions(msg);

		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_FIRST)).submit();
		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_LAST)).submit();
		for (String k : pgs.get(0).keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
			private int p = 0;
			private String currCat = "";
			private Map<String, Page> cats = pgs.get(0);
			private final Consumer<Void> success = s -> {
				if (timeout.get() != null)
					timeout.get().cancel(true);
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			{
				setTimeout(timeout, success, msg, time, unit);
			}

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (u.isBot() || reaction.getName().equals(currCat) || m == null || !event.getMessageId().equals(msg.getId()))
					return;

				boolean update = false;
				if (checkEmote(reaction, PREVIOUS)) {
					if (p > 0) {
						p--;
						update = true;
					}
				} else if (checkEmote(reaction, NEXT)) {
					if (p < pgs.size() - 1) {
						p++;
						update = true;
					}
				} else if (checkEmote(reaction, GOTO_FIRST)) {
					if (p > 0) {
						p = 0;
						update = true;
					}
				} else if (checkEmote(reaction, GOTO_LAST)) {
					if (p < pgs.size() - 1) {
						p = pgs.size() - 1;
						update = true;
					}
				} else if (checkEmote(reaction, CANCEL)) {
					clearReactions(m, success);
					return;
				}

				cats = pgs.get(p);
				if (update) {
					if (faces != null && pgs.size() == faces.size()) {
						Page face = faces.get(p);
						if (face != null) updatePage(m, face);
					}
					currCat = "";
					clearReactions(m);
					m.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
					m.addReaction(paginator.getEmotes().get(CANCEL)).submit();
					m.addReaction(paginator.getEmotes().get(NEXT)).submit();

					for (String k : cats.keySet()) {
						if (EmojiUtils.containsEmoji(k))
							m.addReaction(k).submit();
						else {
							net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
							if (e == null) throw new InvalidEmoteException();
							m.addReaction(e).submit();
						}
					}
				}

				String code = reaction.isEmoji() ? reaction.getName() : reaction.getId();
				Page pg = cats.get(code);
				if (pg != null) {
					updatePage(m, pg);
					currCat = code;
				}

				setTimeout(timeout, success, m, time, unit);

				if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
					event.getReaction().removeReaction(u).submit();
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages while allowing
	 * menu-like navigation akin to {@link #categorize(Message, Map)}.
	 *
	 * @param msg              The {@link Message} sent which will be paginated.
	 * @param paginocategories The pages to be shown. The order of the {@link List} will define
	 *                         the order of the pages.
	 * @param faces            The pages to be shown as default for each index. The {@link List} must have the
	 *                         same amount of indexes as the category {@link List}, else it'll be ignored (can be null).
	 * @param canInteract      {@link Predicate} to determine whether the {@link User}
	 *                         that pressed the button can interact with it or not.
	 * @param fastForward      Whether the {@link Emote#GOTO_FIRST} and {@link Emote#GOTO_LAST} buttons should be shown.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginoCategorize(@Nonnull Message msg, @Nonnull List<Map<String, Page>> paginocategories, @Nullable List<Page> faces, boolean fastForward, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		List<Map<String, Page>> pgs = Collections.unmodifiableList(paginocategories);
		clearReactions(msg);

		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_FIRST)).submit();
		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_LAST)).submit();
		for (String k : pgs.get(0).keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private int p = 0;
			private String currCat = "";
			private Map<String, Page> cats = pgs.get(0);
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				if (canInteract.test(u)) {
					MessageReaction.ReactionEmote reaction = event.getReactionEmote();
					if (u.isBot() || reaction.getName().equals(currCat) || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					boolean update = false;
					if (checkEmote(reaction, PREVIOUS)) {
						if (p > 0) {
							p--;
							update = true;
						}
					} else if (checkEmote(reaction, NEXT)) {
						if (p < pgs.size() - 1) {
							p++;
							update = true;
						}
					} else if (checkEmote(reaction, GOTO_FIRST)) {
						if (p > 0) {
							p = 0;
							update = true;
						}
					} else if (checkEmote(reaction, GOTO_LAST)) {
						if (p < pgs.size() - 1) {
							p = pgs.size() - 1;
							update = true;
						}
					} else if (checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
						return;
					}

					cats = pgs.get(p);
					if (update) {
						if (faces != null && pgs.size() == faces.size()) {
							Page face = faces.get(p);
							if (face != null) updatePage(m, face);
						}
						currCat = "";
						clearReactions(m);
						if (fastForward) m.addReaction(paginator.getEmotes().get(GOTO_FIRST)).submit();
						m.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
						m.addReaction(paginator.getEmotes().get(CANCEL)).submit();
						m.addReaction(paginator.getEmotes().get(NEXT)).submit();
						if (fastForward) m.addReaction(paginator.getEmotes().get(GOTO_LAST)).submit();

						for (String k : cats.keySet()) {
							if (EmojiUtils.containsEmoji(k))
								m.addReaction(k).submit();
							else {
								net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
								if (e == null) throw new InvalidEmoteException();
								m.addReaction(e).submit();
							}
						}
					}

					String code = reaction.isEmoji() ? reaction.getName() : reaction.getId();
					Page pg = cats.get(code);
					if (pg != null) {
						updatePage(m, pg);
						currCat = code;
					}

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages while allowing
	 * menu-like navigation akin to {@link #categorize(Message, Map)}.
	 *
	 * @param msg              The {@link Message} sent which will be paginated.
	 * @param paginocategories The pages to be shown. The order of the {@link List} will define
	 *                         the order of the pages.
	 * @param faces            The pages to be shown as default for each index. The {@link List} must have the
	 *                         same amount of indexes as the category {@link List}, else it'll be ignored (can be null).
	 * @param time             The time before the listener automatically stop listening
	 *                         for further events (recommended: 60).
	 * @param unit             The time's {@link TimeUnit} (recommended:
	 *                         {@link TimeUnit#SECONDS}).
	 * @param fastForward      Whether the {@link Emote#GOTO_FIRST} and {@link Emote#GOTO_LAST} buttons should be shown.
	 * @param canInteract      {@link Predicate} to determine whether the {@link User}
	 *                         that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginoCategorize(@Nonnull Message msg, @Nonnull List<Map<String, Page>> paginocategories, @Nullable List<Page> faces, int time, @Nonnull TimeUnit unit, boolean fastForward, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		List<Map<String, Page>> pgs = Collections.unmodifiableList(paginocategories);
		clearReactions(msg);

		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_FIRST)).submit();
		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_LAST)).submit();
		for (String k : pgs.get(0).keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
			private int p = 0;
			private String currCat = "";
			private Map<String, Page> cats = pgs.get(0);
			private final Consumer<Void> success = s -> {
				if (timeout.get() != null)
					timeout.get().cancel(true);
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			{
				setTimeout(timeout, success, msg, time, unit);
			}

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				if (canInteract.test(u)) {
					MessageReaction.ReactionEmote reaction = event.getReactionEmote();
					if (u.isBot() || reaction.getName().equals(currCat) || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					boolean update = false;
					if (checkEmote(reaction, PREVIOUS)) {
						if (p > 0) {
							p--;
							update = true;
						}
					} else if (checkEmote(reaction, NEXT)) {
						if (p < pgs.size() - 1) {
							p++;
							update = true;
						}
					} else if (checkEmote(reaction, GOTO_FIRST)) {
						if (p > 0) {
							p = 0;
							update = true;
						}
					} else if (checkEmote(reaction, GOTO_LAST)) {
						if (p < pgs.size() - 1) {
							p = pgs.size() - 1;
							update = true;
						}
					} else if (checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
						return;
					}

					cats = pgs.get(p);
					if (update) {
						if (faces != null && pgs.size() == faces.size()) {
							Page face = faces.get(p);
							if (face != null) updatePage(m, face);
						}
						currCat = "";
						clearReactions(m);
						if (fastForward) m.addReaction(paginator.getEmotes().get(GOTO_FIRST)).submit();
						m.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
						m.addReaction(paginator.getEmotes().get(CANCEL)).submit();
						m.addReaction(paginator.getEmotes().get(NEXT)).submit();
						if (fastForward) m.addReaction(paginator.getEmotes().get(GOTO_LAST)).submit();

						for (String k : cats.keySet()) {
							if (EmojiUtils.containsEmoji(k))
								m.addReaction(k).submit();
							else {
								net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
								if (e == null) throw new InvalidEmoteException();
								m.addReaction(e).submit();
							}
						}
					}

					String code = reaction.isEmoji() ? reaction.getName() : reaction.getId();
					Page pg = cats.get(code);
					if (pg != null) {
						updatePage(m, pg);
						currCat = code;
					}

					setTimeout(timeout, success, m, time, unit);

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages while allowing
	 * menu-like navigation akin to {@link #categorize(Message, Map)}.
	 *
	 * @param msg              The {@link Message} sent which will be paginated.
	 * @param paginocategories The pages to be shown. The order of the {@link List} will define
	 *                         the order of the pages.
	 * @param faces            The pages to be shown as default for each index. The {@link List} must have the
	 *                         same amount of indexes as the category {@link List}, else it'll be ignored (can be null).
	 * @param skipAmount       The amount of pages to be skipped when clicking {@link Emote#SKIP_BACKWARD}
	 *                         and {@link Emote#SKIP_FORWARD} buttons.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginoCategorize(@Nonnull Message msg, @Nonnull List<Map<String, Page>> paginocategories, @Nullable List<Page> faces, int skipAmount) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		List<Map<String, Page>> pgs = Collections.unmodifiableList(paginocategories);
		clearReactions(msg);

		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_BACKWARD)).submit();
		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_FORWARD)).submit();
		for (String k : pgs.get(0).keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private int p = 0;
			private String currCat = "";
			private Map<String, Page> cats = pgs.get(0);
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (u.isBot() || reaction.getName().equals(currCat) || m == null || !event.getMessageId().equals(msg.getId()))
					return;

				boolean update = false;
				if (checkEmote(reaction, PREVIOUS)) {
					if (p > 0) {
						p--;
						update = true;
					}
				} else if (checkEmote(reaction, NEXT)) {
					if (p < pgs.size() - 1) {
						p++;
						update = true;
					}
				} else if (checkEmote(reaction, SKIP_BACKWARD)) {
					if (p > 0) {
						p -= (p - skipAmount < 0 ? p : skipAmount);
						update = true;
					}
				} else if (checkEmote(reaction, SKIP_FORWARD)) {
					if (p < pgs.size() - 1) {
						p += (p + skipAmount > pgs.size() ? pgs.size() - p : skipAmount);
						update = true;
					}
				} else if (checkEmote(reaction, CANCEL)) {
					clearReactions(m, success);
					return;
				}

				cats = pgs.get(p);
				if (update) {
					if (faces != null && pgs.size() == faces.size()) {
						Page face = faces.get(p);
						if (face != null) updatePage(m, face);
					}
					currCat = "";
					clearReactions(m);
					m.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
					m.addReaction(paginator.getEmotes().get(CANCEL)).submit();
					m.addReaction(paginator.getEmotes().get(NEXT)).submit();

					for (String k : cats.keySet()) {
						if (EmojiUtils.containsEmoji(k))
							m.addReaction(k).submit();
						else {
							net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
							if (e == null) throw new InvalidEmoteException();
							m.addReaction(e).submit();
						}
					}
				}

				String code = reaction.isEmoji() ? reaction.getName() : reaction.getId();
				Page pg = cats.get(code);
				if (pg != null) {
					updatePage(m, pg);
					currCat = code;
				}

				if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
					event.getReaction().removeReaction(u).submit();
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages while allowing
	 * menu-like navigation akin to {@link #categorize(Message, Map)}.
	 *
	 * @param msg              The {@link Message} sent which will be paginated.
	 * @param paginocategories The pages to be shown. The order of the {@link List} will define
	 *                         the order of the pages.
	 * @param faces            The pages to be shown as default for each index. The {@link List} must have the
	 *                         same amount of indexes as the category {@link List}, else it'll be ignored (can be null).
	 * @param time             The time before the listener automatically stop listening
	 *                         for further events (recommended: 60).
	 * @param unit             The time's {@link TimeUnit} (recommended:
	 *                         {@link TimeUnit#SECONDS}).
	 * @param skipAmount       The amount of pages to be skipped when clicking {@link Emote#SKIP_BACKWARD}
	 *                         and {@link Emote#SKIP_FORWARD} buttons.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginoCategorize(@Nonnull Message msg, @Nonnull List<Map<String, Page>> paginocategories, @Nullable List<Page> faces, int time, @Nonnull TimeUnit unit, int skipAmount) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		List<Map<String, Page>> pgs = Collections.unmodifiableList(paginocategories);
		clearReactions(msg);

		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_BACKWARD)).submit();
		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_FORWARD)).submit();
		for (String k : pgs.get(0).keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
			private int p = 0;
			private String currCat = "";
			private Map<String, Page> cats = pgs.get(0);
			private final Consumer<Void> success = s -> {
				if (timeout.get() != null)
					timeout.get().cancel(true);
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			{
				setTimeout(timeout, success, msg, time, unit);
			}

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				MessageReaction.ReactionEmote reaction = event.getReactionEmote();
				if (u.isBot() || reaction.getName().equals(currCat) || m == null || !event.getMessageId().equals(msg.getId()))
					return;

				boolean update = false;
				if (checkEmote(reaction, PREVIOUS)) {
					if (p > 0) {
						p--;
						update = true;
					}
				} else if (checkEmote(reaction, NEXT)) {
					if (p < pgs.size() - 1) {
						p++;
						update = true;
					}
				} else if (checkEmote(reaction, SKIP_BACKWARD)) {
					if (p > 0) {
						p -= (p - skipAmount < 0 ? p : skipAmount);
						update = true;
					}
				} else if (checkEmote(reaction, SKIP_FORWARD)) {
					if (p < pgs.size() - 1) {
						p += (p + skipAmount > pgs.size() ? pgs.size() - p : skipAmount);
						update = true;
					}
				} else if (checkEmote(reaction, CANCEL)) {
					clearReactions(m, success);
					return;
				}

				cats = pgs.get(p);
				if (update) {
					if (faces != null && pgs.size() == faces.size()) {
						Page face = faces.get(p);
						if (face != null) updatePage(m, face);
					}
					currCat = "";
					clearReactions(m);
					m.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
					m.addReaction(paginator.getEmotes().get(CANCEL)).submit();
					m.addReaction(paginator.getEmotes().get(NEXT)).submit();

					for (String k : cats.keySet()) {
						if (EmojiUtils.containsEmoji(k))
							m.addReaction(k).submit();
						else {
							net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
							if (e == null) throw new InvalidEmoteException();
							m.addReaction(e).submit();
						}
					}
				}

				String code = reaction.isEmoji() ? reaction.getName() : reaction.getId();
				Page pg = cats.get(code);
				if (pg != null) {
					updatePage(m, pg);
					currCat = code;
				}

				setTimeout(timeout, success, m, time, unit);

				if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
					event.getReaction().removeReaction(u).submit();
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages while allowing
	 * menu-like navigation akin to {@link #categorize(Message, Map)}.
	 *
	 * @param msg              The {@link Message} sent which will be paginated.
	 * @param paginocategories The pages to be shown. The order of the {@link List} will define
	 *                         the order of the pages.
	 * @param faces            The pages to be shown as default for each index. The {@link List} must have the
	 *                         same amount of indexes as the category {@link List}, else it'll be ignored (can be null).
	 * @param skipAmount       The amount of pages to be skipped when clicking {@link Emote#SKIP_BACKWARD}
	 *                         and {@link Emote#SKIP_FORWARD} buttons.
	 * @param canInteract      {@link Predicate} to determine whether the {@link User}
	 *                         that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginoCategorize(@Nonnull Message msg, @Nonnull List<Map<String, Page>> paginocategories, @Nullable List<Page> faces, int skipAmount, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		List<Map<String, Page>> pgs = Collections.unmodifiableList(paginocategories);
		clearReactions(msg);

		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_BACKWARD)).submit();
		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_FORWARD)).submit();
		for (String k : pgs.get(0).keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
			private int p = 0;
			private String currCat = "";
			private Map<String, Page> cats = pgs.get(0);
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				if (canInteract.test(u)) {
					MessageReaction.ReactionEmote reaction = event.getReactionEmote();
					if (u.isBot() || reaction.getName().equals(currCat) || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					boolean update = false;
					if (checkEmote(reaction, PREVIOUS)) {
						if (p > 0) {
							p--;
							update = true;
						}
					} else if (checkEmote(reaction, NEXT)) {
						if (p < pgs.size() - 1) {
							p++;
							update = true;
						}
					} else if (checkEmote(reaction, SKIP_BACKWARD)) {
						if (p > 0) {
							p -= (p - skipAmount < 0 ? p : skipAmount);
							update = true;
						}
					} else if (checkEmote(reaction, SKIP_FORWARD)) {
						if (p < pgs.size() - 1) {
							p += (p + skipAmount > pgs.size() ? pgs.size() - p : skipAmount);
							update = true;
						}
					} else if (checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
						return;
					}

					cats = pgs.get(p);
					if (update) {
						if (faces != null && pgs.size() == faces.size()) {
							Page face = faces.get(p);
							if (face != null) updatePage(m, face);
						}
						currCat = "";
						clearReactions(m);
						if (skipAmount > 1) m.addReaction(paginator.getEmotes().get(SKIP_BACKWARD)).submit();
						m.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
						m.addReaction(paginator.getEmotes().get(CANCEL)).submit();
						m.addReaction(paginator.getEmotes().get(NEXT)).submit();
						if (skipAmount > 1) m.addReaction(paginator.getEmotes().get(SKIP_FORWARD)).submit();

						for (String k : cats.keySet()) {
							if (EmojiUtils.containsEmoji(k))
								m.addReaction(k).submit();
							else {
								net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
								if (e == null) throw new InvalidEmoteException();
								m.addReaction(e).submit();
							}
						}
					}

					String code = reaction.isEmoji() ? reaction.getName() : reaction.getId();
					Page pg = cats.get(code);
					if (pg != null) {
						updatePage(m, pg);
						currCat = code;
					}

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages while allowing
	 * menu-like navigation akin to {@link #categorize(Message, Map)}.
	 *
	 * @param msg              The {@link Message} sent which will be paginated.
	 * @param paginocategories The pages to be shown. The order of the {@link List} will define
	 *                         the order of the pages.
	 * @param faces            The pages to be shown as default for each index. The {@link List} must have the
	 *                         same amount of indexes as the category {@link List}, else it'll be ignored (can be null).
	 * @param time             The time before the listener automatically stop listening
	 *                         for further events (recommended: 60).
	 * @param unit             The time's {@link TimeUnit} (recommended:
	 *                         {@link TimeUnit#SECONDS}).
	 * @param skipAmount       The amount of pages to be skipped when clicking {@link Emote#SKIP_BACKWARD}
	 *                         and {@link Emote#SKIP_FORWARD} buttons.
	 * @param canInteract      {@link Predicate} to determine whether the {@link User}
	 *                         that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginoCategorize(@Nonnull Message msg, @Nonnull List<Map<String, Page>> paginocategories, @Nullable List<Page> faces, int time, @Nonnull TimeUnit unit, int skipAmount, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		List<Map<String, Page>> pgs = Collections.unmodifiableList(paginocategories);
		clearReactions(msg);

		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_BACKWARD)).submit();
		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_FORWARD)).submit();
		for (String k : pgs.get(0).keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
			private int p = 0;
			private String currCat = "";
			private Map<String, Page> cats = pgs.get(0);
			private final Consumer<Void> success = s -> {
				if (timeout.get() != null)
					timeout.get().cancel(true);
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			{
				setTimeout(timeout, success, msg, time, unit);
			}

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				if (canInteract.test(u)) {
					MessageReaction.ReactionEmote reaction = event.getReactionEmote();
					if (u.isBot() || reaction.getName().equals(currCat) || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					boolean update = false;
					if (checkEmote(reaction, PREVIOUS)) {
						if (p > 0) {
							p--;
							update = true;
						}
					} else if (checkEmote(reaction, NEXT)) {
						if (p < pgs.size() - 1) {
							p++;
							update = true;
						}
					} else if (checkEmote(reaction, SKIP_BACKWARD)) {
						if (p > 0) {
							p -= (p - skipAmount < 0 ? p : skipAmount);
							update = true;
						}
					} else if (checkEmote(reaction, SKIP_FORWARD)) {
						if (p < pgs.size() - 1) {
							p += (p + skipAmount > pgs.size() ? pgs.size() - p : skipAmount);
							update = true;
						}
					} else if (checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
						return;
					}

					cats = pgs.get(p);
					if (update) {
						if (faces != null && pgs.size() == faces.size()) {
							Page face = faces.get(p);
							if (face != null) updatePage(m, face);
						}
						currCat = "";
						clearReactions(m);
						if (skipAmount > 1) m.addReaction(paginator.getEmotes().get(SKIP_BACKWARD)).submit();
						m.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
						m.addReaction(paginator.getEmotes().get(CANCEL)).submit();
						m.addReaction(paginator.getEmotes().get(NEXT)).submit();
						if (skipAmount > 1) m.addReaction(paginator.getEmotes().get(SKIP_FORWARD)).submit();

						for (String k : cats.keySet()) {
							if (EmojiUtils.containsEmoji(k))
								m.addReaction(k).submit();
							else {
								net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
								if (e == null) throw new InvalidEmoteException();
								m.addReaction(e).submit();
							}
						}
					}

					String code = reaction.isEmoji() ? reaction.getName() : reaction.getId();
					Page pg = cats.get(code);
					if (pg != null) {
						updatePage(m, pg);
						currCat = code;
					}

					setTimeout(timeout, success, m, time, unit);

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages while allowing
	 * menu-like navigation akin to {@link #categorize(Message, Map)}.
	 *
	 * @param msg              The {@link Message} sent which will be paginated.
	 * @param paginocategories The pages to be shown. The order of the {@link List} will define
	 *                         the order of the pages.
	 * @param faces            The pages to be shown as default for each index. The {@link List} must have the
	 *                         same amount of indexes as the category {@link List}, else it'll be ignored (can be null).
	 * @param skipAmount       The amount of pages to be skipped when clicking {@link Emote#SKIP_BACKWARD}
	 *                         and {@link Emote#SKIP_FORWARD} buttons.
	 * @param fastForward      Whether the {@link Emote#GOTO_FIRST} and {@link Emote#GOTO_LAST} buttons should be shown.
	 * @param canInteract      {@link Predicate} to determine whether the {@link User}
	 *                         that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginoCategorize(@Nonnull Message msg, @Nonnull List<Map<String, Page>> paginocategories, @Nullable List<Page> faces, int skipAmount, boolean fastForward, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		List<Map<String, Page>> pgs = Collections.unmodifiableList(paginocategories);
		clearReactions(msg);

		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_FIRST)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_BACKWARD)).submit();
		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_FORWARD)).submit();
		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_LAST)).submit();
		for (String k : pgs.get(0).keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private int p = 0;
			private String currCat = "";
			private Map<String, Page> cats = pgs.get(0);
			private final Consumer<Void> success = s -> {
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				if (canInteract.test(u)) {
					MessageReaction.ReactionEmote reaction = event.getReactionEmote();
					if (u.isBot() || reaction.getName().equals(currCat) || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					boolean update = false;
					if (checkEmote(reaction, PREVIOUS)) {
						if (p > 0) {
							p--;
							update = true;
						}
					} else if (checkEmote(reaction, NEXT)) {
						if (p < pgs.size() - 1) {
							p++;
							update = true;
						}
					} else if (checkEmote(reaction, SKIP_BACKWARD)) {
						if (p > 0) {
							p -= (p - skipAmount < 0 ? p : skipAmount);
							update = true;
						}
					} else if (checkEmote(reaction, SKIP_FORWARD)) {
						if (p < pgs.size() - 1) {
							p += (p + skipAmount > pgs.size() ? pgs.size() - p : skipAmount);
							update = true;
						}
					} else if (checkEmote(reaction, GOTO_FIRST)) {
						if (p > 0) {
							p = 0;
							update = true;
						}
					} else if (checkEmote(reaction, GOTO_LAST)) {
						if (p < pgs.size() - 1) {
							p = pgs.size() - 1;
							update = true;
						}
					} else if (checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
						return;
					}

					cats = pgs.get(p);
					if (update) {
						if (faces != null && pgs.size() == faces.size()) {
							Page face = faces.get(p);
							if (face != null) updatePage(m, face);
						}
						currCat = "";
						clearReactions(m);
						if (fastForward) m.addReaction(paginator.getEmotes().get(GOTO_FIRST)).submit();
						if (skipAmount > 1) m.addReaction(paginator.getEmotes().get(SKIP_BACKWARD)).submit();
						m.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
						m.addReaction(paginator.getEmotes().get(CANCEL)).submit();
						m.addReaction(paginator.getEmotes().get(NEXT)).submit();
						if (skipAmount > 1) m.addReaction(paginator.getEmotes().get(SKIP_FORWARD)).submit();
						if (fastForward) m.addReaction(paginator.getEmotes().get(GOTO_LAST)).submit();

						for (String k : cats.keySet()) {
							if (EmojiUtils.containsEmoji(k))
								m.addReaction(k).submit();
							else {
								net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
								if (e == null) throw new InvalidEmoteException();
								m.addReaction(e).submit();
							}
						}
					}

					String code = reaction.isEmoji() ? reaction.getName() : reaction.getId();
					Page pg = cats.get(code);
					if (pg != null) {
						updatePage(m, pg);
						currCat = code;
					}

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * Adds navigation buttons to the specified {@link Message}/{@link MessageEmbed}
	 * which will navigate through a given {@link List} of pages while allowing
	 * menu-like navigation akin to {@link #categorize(Message, Map)}.
	 *
	 * @param msg              The {@link Message} sent which will be paginated.
	 * @param paginocategories The pages to be shown. The order of the {@link List} will define
	 *                         the order of the pages.
	 * @param faces            The pages to be shown as default for each index. The {@link List} must have the
	 *                         same amount of indexes as the category {@link List}, else it'll be ignored (can be null).
	 * @param time             The time before the listener automatically stop listening
	 *                         for further events (recommended: 60).
	 * @param unit             The time's {@link TimeUnit} (recommended:
	 *                         {@link TimeUnit#SECONDS}).
	 * @param skipAmount       The amount of pages to be skipped when clicking {@link Emote#SKIP_BACKWARD}
	 *                         and {@link Emote#SKIP_FORWARD} buttons.
	 * @param fastForward      Whether the {@link Emote#GOTO_FIRST} and {@link Emote#GOTO_LAST} buttons should be shown.
	 * @param canInteract      {@link Predicate} to determine whether the {@link User}
	 *                         that pressed the button can interact with it or not.
	 * @throws ErrorResponseException          Thrown if the {@link Message} no longer exists
	 *                                         or cannot be accessed when triggering a
	 *                                         {@link GenericMessageReactionEvent}.
	 * @throws InsufficientPermissionException Thrown if this library cannot remove reactions
	 *                                         due to lack of bot permission.
	 * @throws InvalidStateException           Thrown if the library wasn't activated.
	 */
	public static void paginoCategorize(@Nonnull Message msg, @Nonnull List<Map<String, Page>> paginocategories, @Nullable List<Page> faces, int time, @Nonnull TimeUnit unit, int skipAmount, boolean fastForward, @Nonnull Predicate<User> canInteract) throws ErrorResponseException, InsufficientPermissionException, InvalidEmoteException {
		if (!isActivated()) throw new InvalidStateException();
		List<Map<String, Page>> pgs = Collections.unmodifiableList(paginocategories);
		clearReactions(msg);

		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_FIRST)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_BACKWARD)).submit();
		msg.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
		msg.addReaction(paginator.getEmotes().get(CANCEL)).submit();
		msg.addReaction(paginator.getEmotes().get(NEXT)).submit();
		if (skipAmount > 1) msg.addReaction(paginator.getEmotes().get(SKIP_FORWARD)).submit();
		if (fastForward) msg.addReaction(paginator.getEmotes().get(GOTO_LAST)).submit();
		for (String k : pgs.get(0).keySet()) {
			if (EmojiUtils.containsEmoji(k))
				msg.addReaction(k).submit();
			else {
				net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
				if (e == null) throw new InvalidEmoteException();
				msg.addReaction(e).submit();
			}
		}

		handler.addEvent(msg, new ThrowingBiConsumer<>() {
			private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>(null);
			private int p = 0;
			private String currCat = "";
			private Map<String, Page> cats = pgs.get(0);
			private final Consumer<Void> success = s -> {
				if (timeout.get() != null)
					timeout.get().cancel(true);
				handler.removeEvent(msg);
				if (paginator.isDeleteOnCancel()) msg.delete().submit();
			};

			{
				setTimeout(timeout, success, msg, time, unit);
			}

			@Override
			public void acceptThrows(@Nonnull User u, @Nonnull GenericMessageReactionEvent event) {
				Message m = null;
				try {
					m = event.retrieveMessage().submit().get();
				} catch (InterruptedException | ExecutionException ignore) {
				}

				if (canInteract.test(u)) {
					MessageReaction.ReactionEmote reaction = event.getReactionEmote();
					if (u.isBot() || reaction.getName().equals(currCat) || m == null || !event.getMessageId().equals(msg.getId()))
						return;

					boolean update = false;
					if (checkEmote(reaction, PREVIOUS)) {
						if (p > 0) {
							p--;
							update = true;
						}
					} else if (checkEmote(reaction, NEXT)) {
						if (p < pgs.size() - 1) {
							p++;
							update = true;
						}
					} else if (checkEmote(reaction, SKIP_BACKWARD)) {
						if (p > 0) {
							p -= (p - skipAmount < 0 ? p : skipAmount);
							update = true;
						}
					} else if (checkEmote(reaction, SKIP_FORWARD)) {
						if (p < pgs.size() - 1) {
							p += (p + skipAmount > pgs.size() ? pgs.size() - p : skipAmount);
							update = true;
						}
					} else if (checkEmote(reaction, GOTO_FIRST)) {
						if (p > 0) {
							p = 0;
							update = true;
						}
					} else if (checkEmote(reaction, GOTO_LAST)) {
						if (p < pgs.size() - 1) {
							p = pgs.size() - 1;
							update = true;
						}
					} else if (checkEmote(reaction, CANCEL)) {
						clearReactions(m, success);
						return;
					}

					cats = pgs.get(p);
					if (update) {
						if (faces != null && pgs.size() == faces.size()) {
							Page face = faces.get(p);
							if (face != null) updatePage(m, face);
						}
						currCat = "";
						clearReactions(m);
						if (fastForward) m.addReaction(paginator.getEmotes().get(GOTO_FIRST)).submit();
						if (skipAmount > 1) m.addReaction(paginator.getEmotes().get(SKIP_BACKWARD)).submit();
						m.addReaction(paginator.getEmotes().get(PREVIOUS)).submit();
						m.addReaction(paginator.getEmotes().get(CANCEL)).submit();
						m.addReaction(paginator.getEmotes().get(NEXT)).submit();
						if (skipAmount > 1) m.addReaction(paginator.getEmotes().get(SKIP_FORWARD)).submit();
						if (fastForward) m.addReaction(paginator.getEmotes().get(GOTO_LAST)).submit();

						for (String k : cats.keySet()) {
							if (EmojiUtils.containsEmoji(k))
								m.addReaction(k).submit();
							else {
								net.dv8tion.jda.api.entities.Emote e = getOrRetrieveEmote(k);
								if (e == null) throw new InvalidEmoteException();
								m.addReaction(e).submit();
							}
						}
					}

					String code = reaction.isEmoji() ? reaction.getName() : reaction.getId();
					Page pg = cats.get(code);
					if (pg != null) {
						updatePage(m, pg);
						currCat = code;
					}

					setTimeout(timeout, success, m, time, unit);

					if (event.isFromGuild() && event instanceof MessageReactionAddEvent && paginator.isRemoveOnReact()) {
						event.getReaction().removeReaction(u).submit();
					}
				}
			}
		});
	}

	/**
	 * @param msg       The {@link Message} sent which will be paginated.
	 * @param reactions A {@link List} of {@link Emote} which will be added as reactions to the above {@code msg}.
	 */
	private static void addReactions(Message msg, List<Emote> reactions) {
		reactions.forEach(reaction -> msg.addReaction(paginator.getEmotes().get(reaction)).submit());
	}

	/**
	 * Method used to update the current page.
	 * <strong>Must not be called outside of {@link Pages}</strong>.
	 *
	 * @param msg The current {@link Message} object.
	 * @param p   The current {@link Page}.
	 */
	public static void updatePage(@Nonnull Message msg, Page p) {
		if (p == null) throw new NullPageException();

		if (p.getContent() instanceof Message) {
			msg.editMessage((Message) p.getContent()).submit();
		} else if (p.getContent() instanceof MessageEmbed) {
			msg.editMessage((MessageEmbed) p.getContent()).submit();
		}
	}

	/**
	 * Method used to set expiration of the events.
	 * <strong>Must not be called outside of {@link Pages}</strong>.
	 *
	 * @param timeout The {@link CompletableFuture} reference which will contain expiration action.
	 * @param success The {@link Consumer} to be called after expiration.
	 * @param msg     The {@link Message} related to this event.
	 * @param time    How much time before expiration.
	 * @param unit    The {@link TimeUnit} for the expiration time.
	 */
	private static void setTimeout(AtomicReference<ScheduledFuture<?>> timeout, Consumer<Void> success, Message msg, int time, TimeUnit unit) {
		if (timeout.get() != null)
			timeout.get().cancel(true);

		try {
			timeout.set(
					executor.schedule(() -> {
						msg.clearReactions().submit().thenAccept(success);
					}, time, unit)
			);
		} catch (InsufficientPermissionException | IllegalStateException e) {
			timeout.set(
					executor.schedule(() -> {
						msg.getChannel()
								.retrieveMessageById(msg.getId())
								.submit()
								.thenCompose(m -> {
									CompletableFuture<?>[] removeReaction = new CompletableFuture[m.getReactions().size()];

									for (int i = 0; i < m.getReactions().size(); i++) {
										MessageReaction r = m.getReactions().get(i);

										if (!r.isSelf()) continue;

										removeReaction[i] = r.removeReaction().submit();
									}

									return CompletableFuture.allOf(removeReaction).thenAccept(success);
								});
					}, time, unit)
			);
		}
	}

	/**
	 * Utility method used to check if a reaction's {@link net.dv8tion.jda.api.entities.Emote} equals
	 * to given {@link Emote} set during building.
	 * <strong>Must not be called outside of {@link Pages}</strong>.
	 *
	 * @param reaction The reaction returned by {@link ListenerAdapter#onMessageReactionAdd}.
	 * @param emote    The {@link Emote} to check.
	 * @return Whether the reaction's name or ID equals to the {@link Emote}'s definition.
	 */
	private static boolean checkEmote(MessageReaction.ReactionEmote reaction, Emote emote) {
		if (reaction.isEmoji() && reaction.getName().equals(paginator.getEmote(emote)))
			return true;
		else return reaction.isEmote() && reaction.getId().equals(paginator.getEmote(emote));
	}

	/**
	 * Utility method to either retrieve the Emote by using a {@link RestAction} or get from
	 * the cache.
	 * <strong>Must not be called outside of {@link Pages}</strong>.
	 *
	 * @param id The {@link net.dv8tion.jda.api.entities.Emote}'s ID.
	 * @return The {@link net.dv8tion.jda.api.entities.Emote} object if found, else returns null.
	 */
	public static net.dv8tion.jda.api.entities.Emote getOrRetrieveEmote(String id) {
		net.dv8tion.jda.api.entities.Emote e = null;
		if (paginator.getHandler() instanceof JDA) {
			JDA handler = (JDA) paginator.getHandler();

			if (handler.getEmotes().isEmpty()) {
				Guild g = handler.getGuildById(paginator.getEmoteCache().getOrDefault(id, "0"));

				if (g != null) {
					try {
						e = g.retrieveEmoteById(id).submit().get();
					} catch (InterruptedException | ExecutionException ignore) {
					}
				} else if (!paginator.getLookupGuilds().isEmpty()) {
					for (String gid : paginator.getLookupGuilds()) {
						try {
							Guild guild = handler.getGuildById(gid);
							if (guild == null) continue;

							e = guild.retrieveEmoteById(id).submit().get();
							break;
						} catch (ErrorResponseException | InterruptedException | ExecutionException ignore) {
						}
					}
				} else for (Guild guild : handler.getGuilds()) {
					try {
						e = guild.retrieveEmoteById(id).submit().get();
						break;
					} catch (ErrorResponseException | InterruptedException | ExecutionException ignore) {
					}
				}

				if (e != null && e.getGuild() != null)
					paginator.getEmoteCache().put(id, e.getGuild().getId());
			} else e = handler.getEmoteById(id);
		} else if (paginator.getHandler() instanceof ShardManager) {
			ShardManager handler = (ShardManager) paginator.getHandler();

			if (handler.getEmotes().isEmpty()) {
				Guild g = handler.getGuildById(paginator.getEmoteCache().getOrDefault(id, "0"));

				if (g != null) {
					try {
						e = g.retrieveEmoteById(id).submit().get();
					} catch (InterruptedException | ExecutionException ignore) {
					}
				} else if (!paginator.getLookupGuilds().isEmpty()) {
					for (String gid : paginator.getLookupGuilds()) {
						try {
							Guild guild = handler.getGuildById(gid);
							if (guild == null) continue;

							e = guild.retrieveEmoteById(id).submit().get();
							break;
						} catch (ErrorResponseException | InterruptedException | ExecutionException ignore) {
						}
					}
				} else for (Guild guild : handler.getGuilds()) {
					try {
						e = guild.retrieveEmoteById(id).submit().get();
						break;
					} catch (ErrorResponseException | InterruptedException | ExecutionException ignore) {
					}
				}

				if (e != null && e.getGuild() != null)
					paginator.getEmoteCache().put(id, e.getGuild().getId());
			} else e = handler.getEmoteById(id);
		}

		return e;
	}

	/**
	 * Utility method to clear all reactions of a message.
	 *
	 * @param msg The {@link Message} to have reactions removed from.
	 */
	public static void clearReactions(Message msg) {
		try {
			if (msg.getChannel().getType().isGuild())
				msg.clearReactions().submit();
			else for (MessageReaction r : msg.getReactions()) {
				r.removeReaction().submit();
			}
		} catch (InsufficientPermissionException | IllegalStateException e) {
			for (MessageReaction r : msg.getReactions()) {
				r.removeReaction().submit();
			}
		}
	}

	/**
	 * Utility method to clear all reactions of a message.
	 *
	 * @param msg      The {@link Message} to have reactions removed from.
	 * @param callback Action to be executed after removing reactions.
	 */
	public static void clearReactions(Message msg, Consumer<Void> callback) {
		try {
			if (msg.getChannel().getType().isGuild())
				msg.clearReactions().submit();
			else for (MessageReaction r : msg.getReactions()) {
				r.removeReaction().submit();
			}
		} catch (InsufficientPermissionException | IllegalStateException e) {
			for (MessageReaction r : msg.getReactions()) {
				r.removeReaction().submit();
			}
		}

		callback.accept(null);
	}
}
