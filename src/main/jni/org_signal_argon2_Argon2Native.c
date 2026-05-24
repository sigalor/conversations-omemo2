#include <stdlib.h>
#include <string.h>
#include "org_signal_argon2_Argon2Native.h"
#include "argon2.h"

#define SIGNAL_ERROR_NULL_INPUT        (-100)
#define SIGNAL_ERROR_BUFFER_ALLOCATION (-101)
#define SIGNAL_ERROR_JNI_METHOD        (-102)

#define MAX_ENCODED_LEN  512

#if !defined memset_s
static void *(*const volatile memset_s)(void *, int, size_t) = &memset;
#endif

JNIEXPORT jint JNICALL Java_org_signal_argon2_Argon2Native_hash
  (JNIEnv *env,
   jclass clazz,
   jint t,
   jint m,
   jint parallelism,
   jbyteArray jPwd,
   jbyteArray jSalt,
   jbyteArray jHash,
   jobject jEncoded,
   jint argon_type,
   jint version)
{
  char encoded[MAX_ENCODED_LEN];

  if (jPwd  == NULL) return SIGNAL_ERROR_NULL_INPUT;
  if (jSalt == NULL) return SIGNAL_ERROR_NULL_INPUT;
  if (jHash == NULL) return SIGNAL_ERROR_NULL_INPUT;

  uint32_t pwd_size  = (uint32_t) (*env)->GetArrayLength(env, jPwd);
  uint32_t salt_size = (uint32_t) (*env)->GetArrayLength(env, jSalt);
  uint32_t hash_size = (uint32_t) (*env)->GetArrayLength(env, jHash);

  jbyte *hash = malloc(hash_size);
  if (hash == NULL) {
    return SIGNAL_ERROR_BUFFER_ALLOCATION;
  }

  jbyte *pwd  = (*env)->GetByteArrayElements(env, jPwd,  NULL);
  jbyte *salt = (*env)->GetByteArrayElements(env, jSalt, NULL);

  int result = pwd == NULL || salt == NULL
               ? SIGNAL_ERROR_BUFFER_ALLOCATION
               : argon2_hash(t, m, parallelism,
                             pwd,  pwd_size,
                             salt, salt_size,
                             hash, hash_size,
                             encoded, jEncoded == NULL ? 0 : sizeof(encoded),
                             argon_type,
                             version);

  if (pwd != NULL) memset_s(pwd, 0, pwd_size);

  if (result == ARGON2_OK) {
    (*env)->SetByteArrayRegion(env, jHash, 0, hash_size, hash);

    memset_s(hash, 0, hash_size);
  }

  if (result == ARGON2_OK && jEncoded != NULL) {
    jclass    stringBufferClass = (*env)->GetObjectClass(env, jEncoded);
    jmethodID appendMethod      = (*env)->GetMethodID(env, stringBufferClass, "append",
                                                      "(Ljava/lang/String;)Ljava/lang/StringBuffer;");

    if (appendMethod == NULL) {
      result = SIGNAL_ERROR_JNI_METHOD;
    } else {
      (*env)->CallObjectMethod(env, jEncoded, appendMethod, (*env)->NewStringUTF(env, encoded));
    }

    memset_s(encoded, 0, sizeof(encoded));
  }

  if (pwd  != NULL) (*env)->ReleaseByteArrayElements(env, jPwd,  pwd,  JNI_ABORT);
  if (salt != NULL) (*env)->ReleaseByteArrayElements(env, jSalt, salt, JNI_ABORT);

  free(hash);

  return result;
}

JNIEXPORT jint JNICALL Java_org_signal_argon2_Argon2Native_verify
  (JNIEnv *env, jclass clazz, jstring jEncoded, jbyteArray jPwd, jint argon_type)
{
  if (jEncoded == NULL) return SIGNAL_ERROR_NULL_INPUT;
  if (jPwd     == NULL) return SIGNAL_ERROR_NULL_INPUT;

  const char *encoded = NULL;

  uint32_t pwd_size = (uint32_t) (*env)->GetArrayLength(env, jPwd);

  jbyte *pwd = (*env)->GetByteArrayElements(env, jPwd, NULL);

  if (pwd != NULL) encoded = (*env)->GetStringUTFChars(env, jEncoded, NULL);

  int result = pwd == NULL || encoded == NULL
               ? SIGNAL_ERROR_BUFFER_ALLOCATION
               : argon2_verify((char *)encoded, pwd, pwd_size, argon_type);

  if (pwd != NULL) {
    memset_s(pwd, 0, pwd_size);

    (*env)->ReleaseByteArrayElements(env, jPwd, pwd, JNI_ABORT);
  }

  if (encoded != NULL) (*env)->ReleaseStringUTFChars(env, jEncoded, encoded);

  return result;
}

JNIEXPORT jstring JNICALL Java_org_signal_argon2_Argon2Native_resultToString
  (JNIEnv *env, jclass clazz, jint argonResult)
{
  const char *message;

  switch (argonResult) {
    case SIGNAL_ERROR_NULL_INPUT:        message = "Input parameter was NULL";         break;
    case SIGNAL_ERROR_BUFFER_ALLOCATION: message = "Failed to allocate input buffers"; break;
    case SIGNAL_ERROR_JNI_METHOD:        message = "Failed to find method";            break;
    default:                             message = argon2_error_message(argonResult);
  }

  return (*env)->NewStringUTF(env, message);
}
