package de.monocles.chat.pinnedmessage;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.siacs.conversations.persistance.DatabaseBackend;
import io.ipfs.cid.Cid;

public class PinnedMessageRepository {
    private static final String TAG = "PinnedMsgRepo";
    private static final String PINNED_MESSAGES_FILE_V2 = "pinned_messages_v2.enc.json";

    private final Context context;
    private final DatabaseBackend databaseBackend;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public PinnedMessageRepository(Context context) {
        this.context = context.getApplicationContext();
        this.databaseBackend = DatabaseBackend.getInstance(this.context);
        migrateFromJsonToDb();
    }

    private void migrateFromJsonToDb() {
        executorService.submit(() -> {
            File file = new File(context.getFilesDir(), PINNED_MESSAGES_FILE_V2);
            if (!file.exists()) {
                return;
            }
            Log.i(TAG, "Migrating pinned messages from JSON to DB.");
            try (FileInputStream fis = new FileInputStream(file);
                 InputStreamReader reader = new InputStreamReader(fis, StandardCharsets.UTF_8)) {

                GsonBuilder gsonBuilder = new GsonBuilder();
                gsonBuilder.registerTypeHierarchyAdapter(byte[].class, new ByteArrayToBase64TypeAdapter());
                Gson gson = gsonBuilder.create();

                Type listType = new TypeToken<ArrayList<PinnedMessage>>() {}.getType();
                List<PinnedMessage> loadedMessages = gson.fromJson(reader, listType);

                if (loadedMessages != null) {
                    for (PinnedMessage pm : loadedMessages) {
                        String decryptedText = null;
                        if (pm.getEncryptedContent() != null && pm.getIv() != null) {
                            byte[] decryptedBytes = CryptoUtils.decrypt(pm.getIv(), pm.getEncryptedContent());
                            if (decryptedBytes != null) {
                                decryptedText = new String(decryptedBytes, StandardCharsets.UTF_8);
                            }
                        }
                        String accountUuid = databaseBackend.getAccountUuidForConversation(pm.getConversationUuid());
                        databaseBackend.pinMessage(
                                pm.getMessageUuid(),
                                pm.getConversationUuid(),
                                accountUuid,
                                decryptedText,
                                pm.getCid() != null ? pm.getCid().toString() : null,
                                pm.getTimestamp()
                        );
                    }
                    Log.i(TAG, "Successfully migrated " + loadedMessages.size() + " pinned messages.");
                }
                if (!file.delete()) {
                    Log.w(TAG, "Failed to delete old pinned messages JSON file.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error during migration from JSON to DB", e);
            }
        });
    }

    public void pinMessage(String messageUuid, String conversationUuid, String plaintextBody, Cid cid, final OnPinCompleteListener listener) {
        executorService.submit(() -> {
            try {
                String accountUuid = databaseBackend.getAccountUuidForConversation(conversationUuid);
                databaseBackend.pinMessage(messageUuid, conversationUuid, accountUuid, plaintextBody, cid != null ? cid.toString() : null, System.currentTimeMillis());
                if (listener != null) listener.onPinComplete(true);
            } catch (Exception e) {
                Log.e(TAG, "Error pinning message", e);
                if (listener != null) listener.onPinComplete(false);
            }
        });
    }

    public void unpinMessage(String messageUuid, final OnUnpinCompleteListener listener) {
        executorService.submit(() -> {
            try {
                databaseBackend.unpinMessage(messageUuid);
                if (listener != null) listener.onUnpinComplete(true);
            } catch (Exception e) {
                Log.e(TAG, "Error unpinning message", e);
                if (listener != null) listener.onUnpinComplete(false);
            }
        });
    }

    public void delete(String conversationUuid, String messageUuid) {
        executorService.submit(() -> databaseBackend.deletePinnedMessage(conversationUuid, messageUuid));
    }

    public DecryptedPinnedMessageData getLatestDecryptedPinnedMessageForConversation(String conversationUuid) {
        try (Cursor cursor = databaseBackend.getPinnedMessages(conversationUuid)) {
            if (cursor != null && cursor.moveToFirst()) {
                return fromCursor(cursor);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting latest pinned message", e);
        }
        return null;
    }

    public List<DecryptedPinnedMessageData> getAllDecryptedPinnedMessagesForConversation(String conversationUuid) {
        List<DecryptedPinnedMessageData> result = new ArrayList<>();
        try (Cursor cursor = databaseBackend.getPinnedMessages(conversationUuid)) {
            while (cursor != null && cursor.moveToNext()) {
                result.add(fromCursor(cursor));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting all pinned messages", e);
        }
        return result;
    }

    public void getAllDecryptedPinnedMessagesForConversationAsync(String conversationUuid, final OnAllPinsLoadedListener listener) {
        executorService.submit(() -> {
            List<DecryptedPinnedMessageData> result = getAllDecryptedPinnedMessagesForConversation(conversationUuid);
            if (listener != null) {
                listener.onAllPinsLoaded(result);
            }
        });
    }

    private DecryptedPinnedMessageData fromCursor(Cursor cursor) {
        String messageUuid = cursor.getString(cursor.getColumnIndexOrThrow("message_uuid"));
        String conversationUuid = cursor.getString(cursor.getColumnIndexOrThrow("conversation_uuid"));
        String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));
        long timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"));
        String cidString = cursor.getString(cursor.getColumnIndexOrThrow("cid"));
        Cid cid = null;
        if (cidString != null) {
            try {
                cid = Cid.decode(cidString);
            } catch (Exception ignored) {}
        }
        return new DecryptedPinnedMessageData(messageUuid, conversationUuid, body, timestamp, cid);
    }

    public interface OnPinCompleteListener { void onPinComplete(boolean success); }
    public interface OnUnpinCompleteListener { void onUnpinComplete(boolean success); }
    public interface OnAllPinsLoadedListener { void onAllPinsLoaded(List<DecryptedPinnedMessageData> messages); }

    public static class DecryptedPinnedMessageData {
        public final String messageUuid;
        public final String conversationUuid;
        public final String plaintextBody;
        public final long timestamp;
        public final Cid cid;

        public DecryptedPinnedMessageData(String messageUuid, String conversationUuid, String plaintextBody, long timestamp, Cid cid) {
            this.messageUuid = messageUuid;
            this.conversationUuid = conversationUuid;
            this.plaintextBody = plaintextBody;
            this.timestamp = timestamp;
            this.cid = cid;
        }
    }

    private static class ByteArrayToBase64TypeAdapter implements JsonSerializer<byte[]>, JsonDeserializer<byte[]> {
        public byte[] deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return android.util.Base64.decode(json.getAsString(), android.util.Base64.NO_WRAP);
        }

        public JsonElement serialize(byte[] src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(android.util.Base64.encodeToString(src, android.util.Base64.NO_WRAP));
        }
    }
}
