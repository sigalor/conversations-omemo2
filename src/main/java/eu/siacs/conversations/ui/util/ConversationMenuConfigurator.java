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

package eu.siacs.conversations.ui.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.ui.XmppActivity;

public class ConversationMenuConfigurator {

	private static boolean microphoneAvailable = false;

	public static void reloadFeatures(Context context) {
		microphoneAvailable = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_MICROPHONE);
	}

	public static void configureAttachmentMenu(@NonNull Conversation conversation, Menu menu, boolean isTextEmpty) {
		final MenuItem menuAttach = menu.findItem(R.id.action_attach_file);

		final boolean visible;
		if (conversation.getMode() == Conversation.MODE_MULTI) {
			visible = conversation.getAccount().httpUploadAvailable() && conversation.getMucOptions().participating();
		} else {
			visible = true;
		}
		if (menuAttach != null) menuAttach.setVisible(visible);
		// if (visible) menu.findItem(R.id.attach_record_voice).setVisible(microphoneAvailable);
		final int nextEncryption = conversation.getNextEncryption();
		final boolean encryptionNone = nextEncryption == Message.ENCRYPTION_NONE;
		final boolean subjectSupported = encryptionNone
				|| nextEncryption == Message.ENCRYPTION_AXOLOTL_OMEMO2;
		menu.findItem(R.id.attach_subject).setVisible(subjectSupported);
		final boolean liveLocationSupported = encryptionNone
				|| nextEncryption == Message.ENCRYPTION_AXOLOTL_OMEMO2;
		menu.findItem(R.id.attach_live_location).setVisible(liveLocationSupported);
		if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N || isTextEmpty) {
			menu.findItem(R.id.attach_schedule).setVisible(false);
		}
	}

	public static void configureEncryptionMenu(@NonNull Conversation conversation, Menu menu, final XmppActivity activity) {
		final MenuItem menuSecure = menu.findItem(R.id.action_security);

		final boolean participating = conversation.getMode() == Conversational.MODE_SINGLE || conversation.getMucOptions().participating();

		if (!participating) {
			menuSecure.setVisible(false);
			return;
		}

		final MenuItem none = menu.findItem(R.id.encryption_choice_none);
		final MenuItem otr = menu.findItem(R.id.encryption_choice_otr);
		final MenuItem pgp = menu.findItem(R.id.encryption_choice_pgp);
		final MenuItem omemo2 = menu.findItem(R.id.encryption_choice_axolotl_omemo2);

		final int next = conversation.getNextEncryption();

		// PQ OMEMO2 is unconditionally the only encryption mode (OmemoSetting.isAlways() is
		// always true), so there is no plaintext/PGP/OTR/legacy-OMEMO choice left to offer —
		// the submenu just reflects whether this chat is encrypted. It is not, only when the
		// conversation is unsuitable for OMEMO by default (e.g. some broadcast conversations),
		// in which case next is NONE.
		final boolean visible = Config.supportOmemo() && next == Message.ENCRYPTION_AXOLOTL_OMEMO2;

		menuSecure.setVisible(visible);

		if (!visible) {
			return;
		}

		menuSecure.setIcon(R.drawable.ic_lock_omemo2_24dp);
		pgp.setVisible(false);
		none.setVisible(false);
		if (omemo2 != null) omemo2.setVisible(true);
		otr.setVisible(false);

		menuSecure.setTitle(R.string.encrypted_with_omemo2);
		if (omemo2 != null) omemo2.setChecked(true);
	}
}
