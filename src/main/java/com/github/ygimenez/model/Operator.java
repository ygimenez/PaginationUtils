package com.github.ygimenez.model;

import com.github.ygimenez.method.Pages;
import net.dv8tion.jda.api.entities.Message;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class Operator {
	private final Message message;
	private final List<Page> pages = new ArrayList<>();
	private final List<Action> buttons = new ArrayList<>();
	private final EnumMap<Pages.Mode, Flag> flags = new EnumMap<>(Pages.Mode.class);

	private Predicate<Message> validation;
	private Consumer<Message> onClose;
	private int time;
	private TimeUnit unit;
	private boolean showCancel;

	public enum Flag {
		PAGINATE_WITH_SKIP(Pages.Mode.PAGINATE),
		PAGINATE_WITH_FF(Pages.Mode.PAGINATE);

		private final Pages.Mode mode;

		Flag(Pages.Mode mode) {
			this.mode = mode;
		}

		public Pages.Mode getMode() {
			return mode;
		}
	}

	protected Operator(Message message) {
		this.message = message;
	}

	public Message getMessage() {
		return message;
	}

	public Predicate<Message> getValidation() {
		return validation;
	}

	protected void setValidation(Predicate<Message> validation) {
		this.validation = validation;
	}

	public Consumer<Message> getOnClose() {
		return onClose;
	}

	protected void setOnClose(Consumer<Message> onClose) {
		this.onClose = onClose;
	}

	public List<Page> getPages() {
		return pages;
	}

	public List<Action> getButtons() {
		return buttons;
	}

	public EnumMap<Pages.Mode, Flag> getFlags() {
		return flags;
	}

	public int getTime() {
		return time;
	}

	public TimeUnit getUnit() {
		return unit;
	}

	protected void setTimeout(int time, TimeUnit unit) {
		this.time = time;
		this.unit = unit;
	}

	public boolean isShowCancel() {
		return showCancel;
	}

	protected void showCancel() {
		this.showCancel = true;
	}

	public int getButtonSlots() {
		int total = 25;
		for (Flag flag : flags.values()) {
			switch (flag) {
				case PAGINATE_WITH_SKIP:
				case PAGINATE_WITH_FF:
					total -= 2;
					break;
			}
		}

		if (showCancel) total--;

		int groups = (int) pages.stream().map(Page::getGroup).distinct().count();
		total -= groups > 1 ? groups : 0;
		total -= groups == 1 && pages.size() > 1 ? 2 : 0;
		total -= buttons.size();

		return total;
	}
}