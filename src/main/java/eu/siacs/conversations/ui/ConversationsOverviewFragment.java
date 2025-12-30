/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.ui;

import static androidx.recyclerview.widget.ItemTouchHelper.LEFT;
import static androidx.recyclerview.widget.ItemTouchHelper.RIGHT;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.base.Optional;
import com.google.common.collect.Collections2;
import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.FragmentConversationsOverviewBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Story;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.ui.adapter.ConversationAdapter;
import eu.siacs.conversations.ui.adapter.StoryAdapter;
import eu.siacs.conversations.ui.interfaces.OnConversationArchived;
import eu.siacs.conversations.ui.interfaces.OnConversationSelected;
import eu.siacs.conversations.ui.util.MenuDoubleTabUtil;
import eu.siacs.conversations.ui.util.PendingActionHelper;
import eu.siacs.conversations.ui.util.PendingItem;
import eu.siacs.conversations.ui.util.ScrollState;
import eu.siacs.conversations.ui.util.SoftKeyboardUtils;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.EasyOnboardingInvite;
import eu.siacs.conversations.utils.ThemeHelper;
import eu.siacs.conversations.xmpp.jingle.OngoingRtpSession;

import static androidx.recyclerview.widget.ItemTouchHelper.LEFT;
import static androidx.recyclerview.widget.ItemTouchHelper.RIGHT;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class ConversationsOverviewFragment extends XmppFragment implements XmppConnectionService.OnStoriesUpdate {

	private static final String STATE_SCROLL_POSITION =
			ConversationsOverviewFragment.class.getName() + ".scroll_state";

    private static final int REQUEST_CHOOSE_STORY_IMAGE = 0x2b01;
    private Account mSelectedAccount;

    private final List<Conversation> conversations = new ArrayList<>();
	private final List<Story> stories = new ArrayList<>();
	private final PendingItem<Conversation> swipedConversation = new PendingItem<>();
	private final PendingItem<ScrollState> pendingScrollState = new PendingItem<>();
	private FragmentConversationsOverviewBinding binding;
	private ConversationAdapter conversationsAdapter;
	private StoryAdapter storyAdapter;
	private XmppActivity activity;
	private final PendingActionHelper pendingActionHelper = new PendingActionHelper();

	private final ItemTouchHelper.SimpleCallback callback =
			new ItemTouchHelper.SimpleCallback(0, LEFT | RIGHT) {
				@Override
				public boolean onMove(
						@NonNull RecyclerView recyclerView,
						@NonNull RecyclerView.ViewHolder viewHolder,
						@NonNull RecyclerView.ViewHolder target) {
					return false;
				}

				@Override
				public void onChildDraw(
						@NonNull Canvas c,
						@NonNull RecyclerView recyclerView,
						@NonNull RecyclerView.ViewHolder viewHolder,
						float dX,
						float dY,
						int actionState,
						boolean isCurrentlyActive) {
					if (viewHolder
							instanceof
							ConversationAdapter.ConversationViewHolder conversationViewHolder) {
						getDefaultUIUtil()
								.onDraw(
										c,
										recyclerView,
										conversationViewHolder.binding.frame,
										dX,
										dY,
										actionState,
										isCurrentlyActive);
					}
				}

				@Override
				public void clearView(
						@NonNull RecyclerView recyclerView,
						@NonNull RecyclerView.ViewHolder viewHolder) {
					if (viewHolder
							instanceof
							ConversationAdapter.ConversationViewHolder conversationViewHolder) {
						getDefaultUIUtil().clearView(conversationViewHolder.binding.frame);
					}
				}

				@Override
				public float getSwipeEscapeVelocity(final float defaultEscapeVelocity) {
					return 32 * defaultEscapeVelocity;
				}

				@Override
				public void onSwiped(
						final RecyclerView.ViewHolder viewHolder, final int direction) {
					pendingActionHelper.execute();
					int position = viewHolder.getLayoutPosition();
					try {
						swipedConversation.push(conversations.get(position));
					} catch (IndexOutOfBoundsException e) {
						return;
					}
					conversationsAdapter.remove(swipedConversation.peek(), position);
					activity.xmppConnectionService.markRead(swipedConversation.peek());

					if (position == 0 && conversationsAdapter.getItemCount() == 0) {
						final Conversation c = swipedConversation.pop();
						activity.xmppConnectionService.archiveConversation(c);
						return;
					}
					final boolean formerlySelected =
							ConversationFragment.getConversation(getActivity())
									== swipedConversation.peek();
					if (activity instanceof OnConversationArchived) {
						((OnConversationArchived) activity)
								.onConversationArchived(swipedConversation.peek());
					}
					final Conversation c = swipedConversation.peek();
					final int title;
					if (c.getMode() == Conversational.MODE_MULTI) {
						if (c.getMucOptions().isPrivateAndNonAnonymous()) {
							title = R.string.title_undo_swipe_out_group_chat;
						} else {
							title = R.string.title_undo_swipe_out_channel;
						}
					} else {
						title = R.string.title_undo_swipe_out_chat;
					}

					final Snackbar snackbar =
							Snackbar.make(binding.list, title, 5000)
									.setAction(
											R.string.undo,
											v -> {
												pendingActionHelper.undo();
												Conversation conversation =
														swipedConversation.pop();
												conversationsAdapter.insert(conversation, position);
												if (formerlySelected) {
													if (activity
															instanceof OnConversationSelected) {
														((OnConversationSelected) activity)
																.onConversationSelected(c);
													}
												}
												LinearLayoutManager layoutManager =
														(LinearLayoutManager)
																binding.list.getLayoutManager();
												if (position
														> layoutManager
														.findLastVisibleItemPosition()) {
													binding.list.smoothScrollToPosition(position);
												}
											})
									.addCallback(
											new Snackbar.Callback() {
												@Override
												public void onDismissed(
														Snackbar transientBottomBar, int event) {
													switch (event) {
														case DISMISS_EVENT_SWIPE:
														case DISMISS_EVENT_TIMEOUT:
															pendingActionHelper.execute();
															break;
													}
												}
											});

					pendingActionHelper.push(
							() -> {
									if (snackbar.isShownOrQueued()) {
									snackbar.dismiss();
								}
								final Conversation conversation = swipedConversation.pop();
								if (conversation != null) {
									if (!conversation.isRead(activity.xmppConnectionService)
											&& conversation.getMode() == Conversation.MODE_SINGLE) {
										return;
									}
									activity.xmppConnectionService.archiveConversation(c);
								}
							});
					snackbar.show();
				}
			};

	private ItemTouchHelper touchHelper;

	public static Conversation getSuggestion(Activity activity) {
		final Conversation exception;
		Fragment fragment = activity.getFragmentManager().findFragmentById(R.id.main_fragment);
		if (fragment instanceof ConversationsOverviewFragment) {
			exception = ((ConversationsOverviewFragment) fragment).swipedConversation.peek();
		} else {
			exception = null;
		}
		return getSuggestion(activity, exception);
	}

	public static Conversation getSuggestion(Activity activity, Conversation exception) {
		Fragment fragment = activity.getFragmentManager().findFragmentById(R.id.main_fragment);
		if (fragment instanceof ConversationsOverviewFragment) {
			List<Conversation> conversations =
					((ConversationsOverviewFragment) fragment).conversations;
			if (conversations.size() > 0) {
				Conversation suggestion = conversations.get(0);
				if (suggestion == exception) {
					if (conversations.size() > 1) {
						return conversations.get(1);
					}
				} else {
					return suggestion;
				}
			}
		}
		return null;
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		if (savedInstanceState == null) {
			return;
		}
		pendingScrollState.push(savedInstanceState.getParcelable(STATE_SCROLL_POSITION));
	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		if (activity instanceof XmppActivity) {
			this.activity = (XmppActivity) activity;
		} else {
			throw new IllegalStateException(
					"Trying to attach fragment to activity that is not an XmppActivity");
		}
	}

	@Override
	public void onDestroyView() {
		Log.d(Config.LOGTAG, "ConversationsOverviewFragment.onDestroyView()");
		super.onDestroyView();
		this.binding = null;
		this.conversationsAdapter = null;
		this.storyAdapter = null;
		this.touchHelper = null;
	}

	@Override
	public void onDestroy() {
		Log.d(Config.LOGTAG, "ConversationsOverviewFragment.onDestroy()");
		super.onDestroy();
	}

	@Override
	public void onPause() {
		Log.d(Config.LOGTAG, "ConversationsOverviewFragment.onPause()");
		pendingActionHelper.execute();
		activity.xmppConnectionService.removeOnStoriesUpdateListener(this);
		super.onPause();
	}

	@Override
	public void onDetach() {
		super.onDetach();
		this.activity = null;
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setHasOptionsMenu(true);
	}

	@Override
	public View onCreateView(
			final LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		this.binding =
				DataBindingUtil.inflate(
						inflater, R.layout.fragment_conversations_overview, container, false);
		this.binding.fab.setOnClickListener(
				(view) -> StartConversationActivity.launch(getActivity()));

        this.binding.fabStory.setOnClickListener(v -> selectAccountToPublishStory());
		this.conversationsAdapter = new ConversationAdapter(this.activity, this.conversations);
		this.conversationsAdapter.setConversationClickListener(
				(view, conversation) -> {
					if (activity instanceof OnConversationSelected) {
						((OnConversationSelected) activity).onConversationSelected(conversation);
					} else {
						Log.w(
								ConversationsOverviewFragment.class.getCanonicalName(),
								"Activity does not implement OnConversationSelected");
					}
				});
		this.binding.list.setAdapter(this.conversationsAdapter);
		this.binding.list.setLayoutManager(
				new LinearLayoutManager(getActivity(), LinearLayoutManager.VERTICAL, false));
		this.storyAdapter = new StoryAdapter(this.activity, this.stories);
		this.binding.storiesList.setAdapter(this.storyAdapter);
		this.binding.storiesList.setLayoutManager(
				new LinearLayoutManager(getActivity(), LinearLayoutManager.HORIZONTAL, false));
		registerForContextMenu(this.binding.list);
		this.binding.list.addOnScrollListener(ExtendedFabSizeChanger.of(binding.fab));
		if (activity.getPreferences().getBoolean("swipe_to_archive", true)) this.touchHelper = new ItemTouchHelper(this.callback);
		if (activity.getPreferences().getBoolean("swipe_to_archive", true)) this.touchHelper.attachToRecyclerView(this.binding.list);
		return binding.getRoot();
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
		menuInflater.inflate(R.menu.fragment_conversations_overview, menu);
		AccountUtils.showHideMenuItems(menu);
		final MenuItem easyOnboardInvite = menu.findItem(R.id.action_easy_invite);
		MenuItem noteToSelf = menu.findItem(R.id.action_note_to_self);
		easyOnboardInvite.setVisible(EasyOnboardingInvite.anyHasSupport(activity == null ? null : activity.xmppConnectionService));
		if (activity != null && activity.xmppConnectionService != null && activity.xmppConnectionService.isOnboarding()) {
			final MenuItem manageAccounts = menu.findItem(R.id.action_accounts);
			if (manageAccounts != null) manageAccounts.setVisible(false);

			final MenuItem settings = menu.findItem(R.id.action_settings);
			if (settings != null) settings.setVisible(false);
		}
		if (activity == null || activity.xmppConnectionService == null || activity.xmppConnectionService.getAccounts().size() != 1) {
			noteToSelf.setVisible(false);
		}
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
		activity.getMenuInflater().inflate(R.menu.conversations, menu);

		final MenuItem menuMucDetails = menu.findItem(R.id.action_muc_details);
		final MenuItem menuContactDetails = menu.findItem(R.id.action_contact_details);
		final MenuItem menuMute = menu.findItem(R.id.action_mute);
		final MenuItem menuUnmute = menu.findItem(R.id.action_unmute);
		final MenuItem menuOngoingCall = menu.findItem(R.id.action_ongoing_call);
		final MenuItem menuTogglePinned = menu.findItem(R.id.action_toggle_pinned);
		final MenuItem menuArchiveChat = menu.findItem(R.id.action_archive);

		if (menuInfo == null) return;
		int pos = ((AdapterContextMenuInfo) menuInfo).position;
		if (pos < 0) return;
		Conversation conversation = conversations.get(pos);
		if (conversation != null) {
			if (conversation.getMode() == Conversation.MODE_MULTI) {
				menuContactDetails.setVisible(false);
				menuMucDetails.setTitle(conversation.getMucOptions().isPrivateAndNonAnonymous() ? R.string.action_muc_details : R.string.channel_details);
				menuOngoingCall.setVisible(false);
				menuArchiveChat.setTitle(conversation.getMucOptions().isPrivateAndNonAnonymous() ? R.string.leave_group : R.string.action_end_conversation_channel);
			} else {
				final XmppConnectionService service = activity == null ? null : activity.xmppConnectionService;
				final Optional<OngoingRtpSession> ongoingRtpSession = service == null ? Optional.absent() : service.getJingleConnectionManager().getOngoingRtpConnection(conversation.getContact());
				if (ongoingRtpSession.isPresent()) {
					menuOngoingCall.setVisible(true);
				} else {
					menuOngoingCall.setVisible(false);
				}
				menuContactDetails.setVisible(!conversation.withSelf());
				menuMucDetails.setVisible(false);
				menuArchiveChat.setTitle(R.string.action_archive_chat);
			}
			if (conversation.isMuted()) {
				menuMute.setVisible(false);
			} else {
				menuUnmute.setVisible(false);
			}
			if (conversation.getBooleanAttribute(Conversation.ATTRIBUTE_PINNED_ON_TOP, false)) {
				menuTogglePinned.setTitle(R.string.remove_from_favorites);
			} else {
				menuTogglePinned.setTitle(R.string.add_to_favorites);
			}
		}
		super.onCreateContextMenu(menu, view, menuInfo);
	}

	@Override
	public boolean onContextItemSelected(MenuItem item) {
		final var info = ((AdapterContextMenuInfo) item.getMenuInfo());
		if (info == null) return false;

		int pos = info.position;
		if (conversations == null || conversations.size() <= pos || pos < 0) return false;

		Conversation conversation = conversations.get(pos);
		ConversationFragment fragment = new ConversationFragment();
		fragment.setHasOptionsMenu(false);
		fragment.onAttach(activity);
		fragment.reInit(conversation, null);
		boolean r = fragment.onOptionsItemSelected(item);
		refresh();
		return r;
	}

	@Override
	public void onBackendConnected() {
		refresh();
		activity.xmppConnectionService.setOnStoriesUpdateListener(this);
	}

	private void setupSwipe() {
		if (this.touchHelper == null && (activity.xmppConnectionService == null || !activity.xmppConnectionService.isOnboarding())) {
			this.touchHelper = new ItemTouchHelper(this.callback);
			this.touchHelper.attachToRecyclerView(this.binding.list);
		}
	}

	@Override
	public void onSaveInstanceState(Bundle bundle) {
		super.onSaveInstanceState(bundle);
		ScrollState scrollState = getScrollState();
		if (scrollState != null) {
			bundle.putParcelable(STATE_SCROLL_POSITION, scrollState);
		}
	}

	private ScrollState getScrollState() {
		if (this.binding == null) {
			return null;
		}
		LinearLayoutManager layoutManager =
				(LinearLayoutManager) this.binding.list.getLayoutManager();
		int position = layoutManager.findFirstVisibleItemPosition();
		final View view = this.binding.list.getChildAt(0);
		if (view != null) {
			return new ScrollState(position, view.getTop());
		} else {
			return new ScrollState(position, 0);
		}
	}

	@Override
	public void onPrepareOptionsMenu(Menu menu) {
		super.onPrepareOptionsMenu(menu);

		boolean navBarVisible = activity instanceof ConversationsActivity && ((ConversationsActivity) activity).navigationBarVisible();
		MenuItem manageAccount = menu.findItem(R.id.action_account);
		MenuItem manageAccounts = menu.findItem(R.id.action_accounts);
		if (navBarVisible) {
			manageAccount.setVisible(false);
			manageAccounts.setVisible(false);
		} else {
			AccountUtils.showHideMenuItems(menu);
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		Log.d(Config.LOGTAG, "ConversationsOverviewFragment.onStart()");
		if (activity.xmppConnectionService != null) {
			refresh();
		}
		if (activity instanceof ConversationsActivity) {
			boolean showed = ((ConversationsActivity) activity).showNavigationBar();

			if (showed) {
				this.binding.fab.setVisibility(View.GONE);
			} else {
				this.binding.fab.setVisibility(View.VISIBLE);
			}
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		Log.d(Config.LOGTAG, "ConversationsOverviewFragment.onResume()");
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		if (MenuDoubleTabUtil.shouldIgnoreTap()) {
			return false;
		}
		switch (item.getItemId()) {
			case R.id.action_search:
				startActivity(new Intent(getActivity(), SearchActivity.class));
				return true;
			case R.id.action_easy_invite:
				selectAccountToStartEasyInvite();
				return true;
			case R.id.action_note_to_self:
				final List<Account> accounts = activity.xmppConnectionService.getAccounts();
				if (accounts.size() == 1) {
					final Contact self = new Contact(accounts.get(0).getSelfContact());
					Conversation conversation = activity.xmppConnectionService.findOrCreateConversation(self.getAccount(), self.getJid(), false, false, null, true, null);
					SoftKeyboardUtils.hideSoftKeyboard(activity);
					activity.switchToConversation(conversation);
				}
		}
		return super.onOptionsItemSelected(item);
	}

	private void selectAccountToStartEasyInvite() {
		final List<Account> accounts =
				EasyOnboardingInvite.getSupportingAccounts(activity.xmppConnectionService);
		if (accounts.isEmpty()) {
			// This can technically happen if opening the menu item races with accounts reconnecting
			// or something
			Toast.makeText(
							getActivity(),
							R.string.no_active_accounts_support_this,
							Toast.LENGTH_LONG)
					.show();
		} else if (accounts.size() == 1) {
			openEasyInviteScreen(accounts.get(0));
		} else {
			final AtomicReference<Account> selectedAccount = new AtomicReference<>(accounts.get(0));
			final MaterialAlertDialogBuilder alertDialogBuilder =
					new MaterialAlertDialogBuilder(activity);
			alertDialogBuilder.setTitle(R.string.choose_account);
			final String[] asStrings =
					Collections2.transform(accounts, a -> a.getJid().asBareJid().toString())
							.toArray(new String[0]);
			alertDialogBuilder.setSingleChoiceItems(
					asStrings, 0, (dialog, which) -> selectedAccount.set(accounts.get(which)));
			alertDialogBuilder.setNegativeButton(R.string.cancel, null);
			alertDialogBuilder.setPositiveButton(
					R.string.ok, (dialog, which) -> openEasyInviteScreen(selectedAccount.get()));
			alertDialogBuilder.create().show();
		}
	}

	private void openEasyInviteScreen(final Account account) {
		EasyOnboardingInviteActivity.launch(account, activity);
	}

    protected void refresh() {
        if (binding == null || this.activity == null) {
            Log.d(Config.LOGTAG,"ConversationsOverviewFragment.refresh() skipped updated because view binding or activity was null");
            return;
        }

        this.stories.clear();
        this.stories.addAll(
                this.activity.xmppConnectionService.getStories().stream()
                        .collect(Collectors.toMap(
                                story -> story.getContact().asBareJid(),
                                story -> story,
                                (a, b) -> a.getPublished() > b.getPublished() ? a : b
                        ))
                        .values()
        );
        Collections.sort(this.stories, (a,b) -> Long.compare(b.getPublished(), a.getPublished()));

        if (this.stories.isEmpty()) {
            binding.storiesList.setVisibility(View.GONE);
        } else {
            binding.storiesList.setVisibility(View.VISIBLE);
        }
        this.storyAdapter.notifyDataSetChanged();

        this.activity.populateWithOrderedConversations(this.conversations);
        Conversation removed = this.swipedConversation.peek();
        if (removed != null) {
            if (removed.isRead(activity == null ? null : activity.xmppConnectionService)) {
                this.conversations.remove(removed);
            } else {
                pendingActionHelper.execute();
            }
        }
        this.conversationsAdapter.notifyDataSetChanged();
        ScrollState scrollState = pendingScrollState.pop();
        if (scrollState != null) {
            setScrollPosition(scrollState);
        }
        if (activity.xmppConnectionService != null && activity.xmppConnectionService.isOnboarding()) {
            binding.fab.setVisibility(View.GONE);

            if (this.conversations.size() == 1) {
                if (activity instanceof OnConversationSelected) {
                    ((OnConversationSelected) activity).onConversationSelected(this.conversations.get(0));
                } else {
                    Log.w(ConversationsOverviewFragment.class.getCanonicalName(), "Activity does not implement OnConversationSelected");
                }
            }
        } else {
            if (activity instanceof ConversationsActivity) {
                boolean showed = ((ConversationsActivity) activity).showNavigationBar();

                if (showed) {
                    this.binding.fab.setVisibility(View.GONE);
                } else {
                    this.binding.fab.setVisibility(View.VISIBLE);
                }
            }
        }
        if (activity.getPreferences().getBoolean("swipe_to_archive", true)) setupSwipe();

        if (activity.xmppConnectionService == null || binding == null || binding.overviewSnackbar == null) return;
        binding.overviewSnackbar.setVisibility(View.GONE);
        for (final var account : activity.xmppConnectionService.getAccounts()) {
            if (activity.getPreferences().getBoolean("no_mam_pref_warn:" + account.getUuid(), false)) continue;
            if (account.mamPrefs() != null && !"always".equals(account.mamPrefs().getAttribute("default"))) {
                binding.overviewSnackbar.setVisibility(View.VISIBLE);
                binding.overviewSnackbarMessage.setText(R.string.your_account + " " + account.getJid().asBareJid().toString() + " " + R.string.archiving_not_enabled_text);
                binding.overviewSnackbarAction.setOnClickListener((v) -> {
                    final var prefs = account.mamPrefs();
                    prefs.setAttribute("default", "always");
                    activity.xmppConnectionService.pushMamPreferences(account, prefs);
                    refresh();
                });

                binding.overviewSnackbarAction.setOnLongClickListener((v) -> {
                    PopupMenu popupMenu = new PopupMenu(getActivity(), v);
                    popupMenu.inflate(R.menu.mam_pref_fix);
                    popupMenu.setOnMenuItemClickListener(menuItem -> {
                        switch (menuItem.getItemId()) {
                            case R.id.ignore:
                                final var editor = activity.getPreferences().edit();
                                editor.putBoolean("no_mam_pref_warn:" + account.getUuid(), true).apply();
                                editor.apply();
                                refresh();
                                return true;
                        }
                        return true;
                    });
                    popupMenu.show();
                    return true;
                });
                break;
            }
        }
    }

	private void setScrollPosition(ScrollState scrollPosition) {
		if (scrollPosition != null) {
			LinearLayoutManager layoutManager =
					(LinearLayoutManager) binding.list.getLayoutManager();
			layoutManager.scrollToPositionWithOffset(
					scrollPosition.position, scrollPosition.offset);
		}
	}

	@Override
	public void onStoriesUpdate() {
		activity.runOnUiThread(this::refresh);
	}

    @Override
    public void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == REQUEST_CHOOSE_STORY_IMAGE) {
                if (data != null && mSelectedAccount != null) {
                    final Uri uri = data.getData();
                    if (uri == null) {
                        return;
                    }
                    final String mimeType = activity.getContentResolver().getType(uri);

                    final EditText input = new EditText(getActivity());
                    input.setHint(R.string.title_optional);

                    new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.add_story_title)
                            .setView(input)
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.publish, (dialog, which) -> {
                                final String title = input.getText().toString();
                                Toast.makeText(activity, R.string.uploading_story, Toast.LENGTH_SHORT).show();

                                // Use a dummy conversation with the user's own JID
                                final Conversation selfConversation = activity.xmppConnectionService.findOrCreateConversation(mSelectedAccount, mSelectedAccount.getJid().asBareJid(), false, false);

                                activity.xmppConnectionService.attachFileToConversation(selfConversation, uri, mimeType, null, new UiCallback<Message>() {
                                    @Override
                                    public void success(Message message) {
                                        // This callback is triggered after the message is sent.
                                        // Now we can get the URL and publish the story.
                                        final String url = message.getFileParams().url.toString();
                                        if (url != null) {
                                            activity.xmppConnectionService.publishStory(mSelectedAccount, url, mimeType, title, new UiCallback<Void>() {
                                                @Override
                                                public void success(Void aVoid) {
                                                    activity.runOnUiThread(() -> Toast.makeText(activity, R.string.story_published, Toast.LENGTH_SHORT).show());
                                                    // Clean up the dummy message
                                                    activity.xmppConnectionService.deleteMessage(message);
                                                }

                                                @Override
                                                public void error(int errorCode, Void object) {
                                                    activity.runOnUiThread(() -> Toast.makeText(activity, errorCode, Toast.LENGTH_SHORT).show());
                                                    activity.xmppConnectionService.deleteMessage(message);
                                                }

                                                @Override
                                                public void userInputRequired(android.app.PendingIntent pi, Void object) {
                                                    // not used
                                                }
                                            });
                                        } else {
                                            error(R.string.upload_failed_server_not_found, message);
                                        }
                                    }

                                    @Override
                                    public void error(int errorCode, Message object) {
                                        activity.runOnUiThread(() -> Toast.makeText(activity, errorCode, Toast.LENGTH_SHORT).show());
                                    }

                                    @Override
                                    public void userInputRequired(android.app.PendingIntent pi, Message object) {
                                        // not used
                                    }
                                });
                            })
                            .create()
                            .show();
                }
            }
        }
    }

    private void selectAccountToPublishStory() {
        final List<Account> accounts = activity.xmppConnectionService.getAccounts().stream().filter(Account::isEnabled).collect(Collectors.toList());
        if (accounts.isEmpty()) {
            Toast.makeText(getActivity(), R.string.no_active_account, Toast.LENGTH_SHORT).show();
        } else if (accounts.size() == 1) {
            openStoryImagePicker(accounts.get(0));
        } else {
            final AtomicReference<Account> selectedAccount = new AtomicReference<>(accounts.get(0));
            final MaterialAlertDialogBuilder alertDialogBuilder = new MaterialAlertDialogBuilder(activity);
            alertDialogBuilder.setTitle(R.string.choose_account);
            final String[] asStrings =
                    accounts.stream().map(a -> a.getJid().asBareJid().toString()).toArray(String[]::new);
            alertDialogBuilder.setSingleChoiceItems(
                    asStrings, 0, (dialog, which) -> selectedAccount.set(accounts.get(which)));
            alertDialogBuilder.setNegativeButton(R.string.cancel, null);
            alertDialogBuilder.setPositiveButton(
                    R.string.ok, (dialog, which) -> openStoryImagePicker(selectedAccount.get()));
            alertDialogBuilder.create().show();
        }
    }

    private void openStoryImagePicker(Account account) {
        this.mSelectedAccount = account;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_CHOOSE_STORY_IMAGE);
    }

}
