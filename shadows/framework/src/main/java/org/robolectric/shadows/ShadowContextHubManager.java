package org.robolectric.shadows;

import android.hardware.location.ContextHubClient;
import android.hardware.location.ContextHubInfo;
import android.hardware.location.ContextHubManager;
import android.hardware.location.ContextHubTransaction;
import android.hardware.location.NanoAppInstanceInfo;
import android.hardware.location.NanoAppState;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.robolectric.annotation.HiddenApi;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** Shadow for {@link ContextHubManager}. */
@Implements(
    value = ContextHubManager.class,
    minSdk = VERSION_CODES.N,
    isInAndroidSdk = false,
    looseSignatures = true)
public class ShadowContextHubManager {
  private static final List<ContextHubInfo> contextHubInfoList = new ArrayList<>();
  private static final Map<Integer, NanoAppInstanceInfo> nanoappUidToInfo = new HashMap<>();
  private static final Map<ContextHubInfo, Set<Integer>> contextHubToNanoappUid = new HashMap<>();

  static {
    contextHubInfoList.add(new ContextHubInfo());
  }

  @Resetter
  public static void reset() {
    nanoappUidToInfo.clear();
    contextHubToNanoappUid.clear();
  }

  /** Adds a nanoapp to the list of nanoapps that are supported by the provided contexthubinfo. */
  public static void addNanoapp(
      ContextHubInfo info, int nanoappUid, long nanoappId, int nanoappVersion) {
    NanoAppInstanceInfo instanceInfo;
    if (VERSION.SDK_INT >= VERSION_CODES.P) {
      instanceInfo = createInstanceInfoP(info, nanoappUid, nanoappId, nanoappVersion);
    } else {
      instanceInfo = createInstanceInfoPreP(nanoappId, nanoappVersion);
    }

    Set<Integer> nanoappUids = contextHubToNanoappUid.get(info);
    if (nanoappUids == null) {
      nanoappUids = new HashSet<>();
    }
    nanoappUids.add(nanoappUid);
    contextHubToNanoappUid.put(info, nanoappUids);
    nanoappUidToInfo.put(nanoappUid, instanceInfo);
  }

  /** Creates a {@link NanoAppInstanceInfo} based on APIs available in P and later. */
  public static NanoAppInstanceInfo createInstanceInfoP(
      ContextHubInfo info, int nanoappUid, long nanoappId, int nanoappVersion) {
    return new NanoAppInstanceInfo(nanoappUid, nanoappId, nanoappVersion, info.getId());
  }

  /** Creates a {@link NanoAppInstanceInfo} based on APIs available prior to P. */
  public static NanoAppInstanceInfo createInstanceInfoPreP(long nanoappId, int nanoappVersion) {
    NanoAppInstanceInfo instanceInfo = new NanoAppInstanceInfo();
    ReflectionHelpers.callInstanceMethod(
        NanoAppInstanceInfo.class,
        instanceInfo,
        "setAppId",
        ClassParameter.from(long.class, nanoappId));
    ReflectionHelpers.callInstanceMethod(
        NanoAppInstanceInfo.class,
        instanceInfo,
        "setAppVersion",
        ClassParameter.from(int.class, nanoappVersion));
    return instanceInfo;
  }

  /**
   * Provides a list with fake {@link ContextHubInfo}s.
   *
   * <p>{@link ContextHubInfo} describes an optional physical chip on the device. This does not
   * exist in test; this implementation allows to avoid possible NPEs.
   */
  @Implementation(minSdk = VERSION_CODES.P)
  @HiddenApi
  protected List<ContextHubInfo> getContextHubs() {
    return contextHubInfoList;
  }

  @Implementation(minSdk = VERSION_CODES.P)
  @HiddenApi
  protected Object /* ContextHubClient */ createClient(
      Object /* ContextHubInfo */ contextHubInfo,
      Object /* ContextHubClientCallback */ contextHubClientCallback) {
    return ReflectionHelpers.newInstance(ContextHubClient.class);
  }

  @Implementation(minSdk = VERSION_CODES.P)
  @HiddenApi
  protected Object /* ContextHubClient */ createClient(
      Object /* ContextHubInfo */ contextHubInfo,
      Object /* ContextHubClientCallback */ contextHubClientCallback,
      Object /* Executor */ executor) {
    return ReflectionHelpers.newInstance(ContextHubClient.class);
  }

  @Implementation(minSdk = VERSION_CODES.P)
  @HiddenApi
  protected Object queryNanoApps(ContextHubInfo hubInfo) {
    @SuppressWarnings("unchecked")
    ContextHubTransaction<List<NanoAppState>> transaction =
        ReflectionHelpers.callConstructor(
            ContextHubTransaction.class,
            ClassParameter.from(int.class, ContextHubTransaction.TYPE_QUERY_NANOAPPS));
    Set<Integer> uids = contextHubToNanoappUid.get(hubInfo);
    List<NanoAppState> nanoappStates = new ArrayList<>();
    if (uids == null) {
      uids = new HashSet<>();
    }

    for (Integer uid : uids) {
      NanoAppInstanceInfo info = nanoappUidToInfo.get(uid);
      if (info == null) {
        continue;
      }
      nanoappStates.add(
          new NanoAppState(info.getAppId(), info.getAppVersion(), true /* enabled */));
    }
    @SuppressWarnings("unchecked")
    ContextHubTransaction.Response<List<NanoAppState>> response =
        ReflectionHelpers.newInstance(ContextHubTransaction.Response.class);
    ReflectionHelpers.setField(response, "mResult", ContextHubTransaction.RESULT_SUCCESS);
    ReflectionHelpers.setField(response, "mContents", nanoappStates);
    ReflectionHelpers.callInstanceMethod(
        transaction, "setResponse", ClassParameter.from(response.getClass(), response));
    return transaction;
  }

  /**
   * Provides an array of fake handles.
   *
   * <p>These describe an optional physical chip on the device which does not exist during testing.
   * This implementation enables testing of classes that utilize these APIs.
   */
  @Implementation
  @HiddenApi
  protected int[] getContextHubHandles() {
    int[] handles = new int[contextHubInfoList.size()];
    for (int i = 0; i < handles.length; i++) {
      handles[i] = i;
    }
    return handles;
  }

  @Implementation
  @HiddenApi
  protected ContextHubInfo getContextHubInfo(int hubHandle) {
    if (hubHandle < 0 || hubHandle >= contextHubInfoList.size()) {
      return null;
    }

    return contextHubInfoList.get(hubHandle);
  }

  @Implementation
  @HiddenApi
  protected NanoAppInstanceInfo getNanoAppInstanceInfo(int nanoAppHandle) {
    return nanoappUidToInfo.get(nanoAppHandle);
  }
}
