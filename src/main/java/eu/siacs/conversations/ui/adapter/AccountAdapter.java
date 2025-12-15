package eu.siacs.conversations.ui.adapter;import android.annotation.SuppressLint;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.color.MaterialColors;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ItemAccountBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import java.util.Collections;
import java.util.List;

public class AccountAdapter extends RecyclerView.Adapter<AccountAdapter.ViewHolder> {

    private final XmppActivity activity;
    private final List<Account> accounts;
    private final boolean showStateButton;
    private final OnAccountClickListener onAccountClickListener;
    private final OnStartDragListener mDragStartListener;
    private OnContextAccountSelected contextAccountListener;

    public interface OnAccountClickListener {
        void onAccountClick(Account account);
    }

    public interface OnStartDragListener {
        void onStartDrag(RecyclerView.ViewHolder viewHolder);
    }

    // New Interface for Context Menu
    public interface OnContextAccountSelected {
        void onContextAccountSelected(Account account);
    }

    public interface OnAccountMovedListener {
        void onAccountMoved();
    }

    // Add a field
    private final OnAccountMovedListener mOnAccountMovedListener;

    // Update Constructor to accept the listener
    public AccountAdapter(XmppActivity activity, List<Account> accounts, OnAccountClickListener onAccountClickListener, OnStartDragListener dragStartListener, OnContextAccountSelected contextAccountListener, OnAccountMovedListener onAccountMovedListener) {
        this.activity = activity;
        this.accounts = accounts;
        this.showStateButton = true;
        this.onAccountClickListener = onAccountClickListener;
        this.mDragStartListener = dragStartListener;
        this.contextAccountListener = contextAccountListener;
        this.mOnAccountMovedListener = onAccountMovedListener;
    }

    public void setContextAccountListener(OnContextAccountSelected listener) {
        this.contextAccountListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAccountBinding binding = DataBindingUtil.inflate(
                LayoutInflater.from(parent.getContext()),
                R.layout.item_account,
                parent,
                false);
        return new ViewHolder(binding);
    }

    @Override
    public int getItemCount() {
        return accounts.size();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final Account account = accounts.get(position);

        holder.binding.accountJid.setText(account.getJid().asBareJid().toString());
        AvatarWorkerTask.loadAvatar(account, holder.binding.accountImage, R.dimen.avatar);
        holder.binding.accountStatus.setText(
                activity.getString(account.getStatus().getReadableId()));

        switch (account.getStatus()) {
            case ONLINE:
                holder.binding.accountStatus.setTextColor(
                        MaterialColors.getColor(holder.binding.accountStatus, androidx.appcompat.R.attr.colorPrimary));
                break;
            case DISABLED:
            case LOGGED_OUT:
            case CONNECTING:
                holder.binding.accountStatus.setTextColor(
                        MaterialColors.getColor(holder.binding.accountStatus, com.google.android.material.R.attr.colorOnSurfaceVariant));
                break;
            default:
                holder.binding.accountStatus.setTextColor(
                        MaterialColors.getColor(holder.binding.accountStatus, androidx.appcompat.R.attr.colorError));
                break;
        }

        if (account.isOnlineAndConnected()) {
            holder.binding.verificationIndicator.setVisibility(View.VISIBLE);
            if (account.getXmppConnection() != null && account.getXmppConnection().resolverAuthenticated()) {
                if (account.getXmppConnection().daneVerified()) {
                    holder.binding.verificationIndicator.setImageResource(R.drawable.shield_verified);
                } else {
                    holder.binding.verificationIndicator.setImageResource(R.drawable.shield);
                }
            } else {
                holder.binding.verificationIndicator.setImageResource(R.drawable.shield_question);
            }
        } else {
            holder.binding.verificationIndicator.setVisibility(View.GONE);
        }

        final boolean isDisabled = (account.getStatus() == Account.State.DISABLED);
        holder.binding.tglAccountStatus.setOnCheckedChangeListener(null);
        holder.binding.tglAccountStatus.setChecked(!isDisabled);

        if (this.showStateButton) {
            holder.binding.tglAccountStatus.setVisibility(View.VISIBLE);
        } else {
            holder.binding.tglAccountStatus.setVisibility(View.GONE);
        }

        holder.binding.tglAccountStatus.setOnCheckedChangeListener((compoundButton, b) -> {
            if (b == isDisabled && activity instanceof OnTglAccountState) {
                ((OnTglAccountState) activity).onClickTglAccountState(account, b);
            }
        });

        if (activity.xmppConnectionService != null && activity.xmppConnectionService.getAccounts().size() > 1) {
            holder.binding.frame.setBackgroundColor(account.getColor(activity.isDark()));
        }

        holder.binding.accountStatusMessage.setText(account.getPresenceStatusMessage());

        holder.binding.getRoot().setOnClickListener(v -> {
            if (onAccountClickListener != null) {
                onAccountClickListener.onAccountClick(account);
            }
        });

        // --- Context Menu Wiring ---
        holder.binding.getRoot().setOnCreateContextMenuListener((menu, v, menuInfo) -> {
            if (contextAccountListener != null) {
                // 1. Notify activity which account is selected
                contextAccountListener.onContextAccountSelected(account);
                // 2. Ask activity to populate the menu
                activity.onCreateContextMenu(menu, v, menuInfo);
            }
        });

        // Setup Drag Handle
        try {
            View dragHandle = holder.binding.getRoot().findViewById(R.id.drag_handle);
            if (dragHandle != null) {
                dragHandle.setVisibility(View.VISIBLE);
                dragHandle.setOnTouchListener((v, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        if (mDragStartListener != null) {
                            mDragStartListener.onStartDrag(holder);
                        }
                    }
                    return false;
                });
            }
        } catch (Exception ignored) {}
    }

    public boolean onItemMove(int fromPosition, int toPosition) {
        if (fromPosition < toPosition) {
            for (int i = fromPosition; i < toPosition; i++) {
                Collections.swap(accounts, i, i + 1);
            }
        } else {
            for (int i = fromPosition; i > toPosition; i--) {
                Collections.swap(accounts, i, i - 1);
            }
        }
        notifyItemMoved(fromPosition, toPosition);

        // Notify that data has changed so we can persist
        if (mOnAccountMovedListener != null) {
            mOnAccountMovedListener.onAccountMoved();
        }
        return true;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final ItemAccountBinding binding;
        private ViewHolder(ItemAccountBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    public interface OnTglAccountState {
        void onClickTglAccountState(Account account, boolean state);
    }
}
