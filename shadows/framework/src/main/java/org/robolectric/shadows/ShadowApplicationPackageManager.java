package org.robolectric.shadows;

import static android.content.pm.PackageManager.*;
import static android.os.Build.VERSION_CODES.JELLY_BEAN;
import static android.os.Build.VERSION_CODES.JELLY_BEAN_MR1;
import static android.os.Build.VERSION_CODES.M;
import static android.os.Build.VERSION_CODES.N;

import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.UserIdInt;
import android.app.ApplicationPackageManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.*;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageParser.Activity;
import android.content.pm.PackageParser.Package;
import android.content.pm.PackageParser.Service;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.*;
import android.os.storage.VolumeInfo;
import android.util.Pair;

import java.util.*;

import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@Implements(value = ApplicationPackageManager.class, isInAndroidSdk = false, looseSignatures = true)
public class ShadowApplicationPackageManager extends ShadowPackageManager {

  @Implementation
  public List<PackageInfo> getInstalledPackages(int flags) {
    List<PackageInfo> result = new ArrayList<>();
    for (PackageInfo packageInfo : packageInfos.values()) {
      if (applicationEnabledSettingMap.get(packageInfo.packageName)
          != COMPONENT_ENABLED_STATE_DISABLED
          || (flags & MATCH_UNINSTALLED_PACKAGES) == MATCH_UNINSTALLED_PACKAGES) {
            result.add(packageInfo);
          }
    }

    return result;
  }

  @Implementation
  public ActivityInfo getActivityInfo(ComponentName component, int flags) throws NameNotFoundException {
    String activityName = component.getClassName();
    String packageName = component.getPackageName();
    PackageInfo packageInfo = packageInfos.get(packageName);

    if (packageInfo != null) {
      for (ActivityInfo activity : packageInfo.activities) {
        if (activityName.equals(activity.name)) {
          ActivityInfo result = new ActivityInfo(activity);
          if ((flags & GET_META_DATA) != 0) {
            result.metaData = activity.metaData;
          }

          return result;
        }
      }
    }

    // TODO: Should throw a NameNotFoundException
    // In the cases where there is no manifest entry for the activity, e.g: a test that creates
    // simply an android.app.Activity just return what we have.
    ActivityInfo result = new ActivityInfo();
    result.name = activityName;
    result.packageName = packageName;
    result.applicationInfo = new ApplicationInfo();
    result.applicationInfo.packageName = packageName;
    return result;
  }

  @Implementation
  public boolean hasSystemFeature(String name) {
    return systemFeatureList.containsKey(name) ? systemFeatureList.get(name) : false;
  }

  @Implementation
  public int getComponentEnabledSetting(ComponentName componentName) {
    ComponentState state = componentList.get(componentName);
    return state != null ? state.newState : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
  }

  @Implementation
  public @Nullable String getNameForUid(int uid) {
    return namesForUid.get(uid);
  }

  @Implementation
  public @Nullable String[] getPackagesForUid(int uid) {
    String[] packageNames = packagesForUid.get(uid);
    if (packageNames != null) {
      return packageNames;
    }

    Set<String> results = new HashSet<>();
    for (PackageInfo packageInfo : packageInfos.values()) {
      if (packageInfo.applicationInfo != null && packageInfo.applicationInfo.uid == uid) {
        results.add(packageInfo.packageName);
      }
    }

    return results.isEmpty()
        ? null
        :results.toArray(new String[results.size()]);
  }

  @Implementation
  public int getApplicationEnabledSetting(String packageName) {
    try {
        getPackageInfo(packageName, -1);
    } catch (NameNotFoundException e) {
        throw new IllegalArgumentException(e);
    }

    return applicationEnabledSettingMap.get(packageName);
  }

  @Implementation
  public ProviderInfo getProviderInfo(ComponentName component, int flags) throws NameNotFoundException {
    String packageName = component.getPackageName();

    PackageInfo packageInfo = packageInfos.get(packageName);
    if (packageInfo != null && packageInfo.providers != null) {
      for (ProviderInfo provider : packageInfo.providers) {
        if (resolvePackageName(packageName, component).equals(provider.name)) {
          ProviderInfo result = new ProviderInfo();
          result.packageName = provider.packageName;
          result.name = provider.name;
          result.authority = provider.authority;
          result.readPermission = provider.readPermission;
          result.writePermission = provider.writePermission;
          result.pathPermissions = provider.pathPermissions;

          if ((flags & GET_META_DATA) != 0) {
            result.metaData = provider.metaData;
          }
          return result;
        }
      }
    }

    throw new NameNotFoundException("Package not found: " + packageName);
  }

  @Implementation
  public void setComponentEnabledSetting(ComponentName componentName, int newState, int flags) {
    componentList.put(componentName, new ComponentState(newState, flags));
  }

  @Override @Implementation
  public void setApplicationEnabledSetting(String packageName, int newState, int flags) {
    applicationEnabledSettingMap.put(packageName, newState);
  }

  @Implementation
  public ResolveInfo resolveActivity(Intent intent, int flags) {
    List<ResolveInfo> candidates = queryIntentActivities(intent, flags);
    return candidates.isEmpty() ? null : candidates.get(0);
  }

  @Implementation
  public ProviderInfo resolveContentProvider(String name, int flags) {
    for (PackageInfo packageInfo : packageInfos.values()) {
      if (packageInfo.providers == null) continue;

      for (ProviderInfo providerInfo : packageInfo.providers) {
        if (name.equals(providerInfo.authority)) { // todo: support multiple authorities
          return providerInfo;
        }
      }
    }

    return null;
  }

  @Implementation
  public ProviderInfo resolveContentProviderAsUser(String name, int flags, @UserIdInt int userId) {
    return null;
  }

  @Implementation
  public PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException {
    PackageInfo info = packageInfos.get(packageName);
    if (info != null) {
      if (applicationEnabledSettingMap.get(packageName) == COMPONENT_ENABLED_STATE_DISABLED
          && (flags & MATCH_UNINSTALLED_PACKAGES) != MATCH_UNINSTALLED_PACKAGES) {
        throw new NameNotFoundException("Package is disabled, can't find");
      }
      return info;
    } else {
      throw new NameNotFoundException(packageName);
    }
  }

  @Implementation
  public List<ResolveInfo> queryIntentServices(Intent intent, int flags) {
    // Check the manually added resolve infos first.
    List<ResolveInfo> resolveInfos = queryIntent(intent, flags);
    if (!resolveInfos.isEmpty()) {
      return resolveInfos;
    }

    resolveInfos = new ArrayList<>();
    for (Package appPackage : packages.values()) {
      if (resolveInfos.isEmpty()) {
        for (Service service : appPackage.services) {
          IntentFilter intentFilter = matchIntentFilter(intent, service.intents, flags);
          if (intentFilter != null) {
            resolveInfos.add(getResolveInfo(service, intentFilter));
          }
        }
      }
    }

    return resolveInfos;
  }

  @Implementation
  public List<ResolveInfo> queryIntentActivities(Intent intent, int flags) {
    List<ResolveInfo> resolveInfoList = queryIntent(intent, flags);

    if (resolveInfoList.isEmpty() && queryIntentImplicitly) {
      resolveInfoList = queryImplicitIntent(intent, flags);
    }

    // If the flag is set, no further filtering will happen.
    if ((flags & PackageManager.MATCH_ALL) == PackageManager.MATCH_ALL) {
      return resolveInfoList;
    }

    // Create a copy of the list for filtering
    resolveInfoList = new ArrayList<>(resolveInfoList);

    if ((flags & PackageManager.MATCH_SYSTEM_ONLY) == PackageManager.MATCH_SYSTEM_ONLY) {
      for (Iterator<ResolveInfo> iterator = resolveInfoList.iterator(); iterator.hasNext();) {
        ResolveInfo resolveInfo = iterator.next();
        if (resolveInfo.activityInfo == null || resolveInfo.activityInfo.applicationInfo == null) {
          iterator.remove();
        } else {
          final int applicationFlags = resolveInfo.activityInfo.applicationInfo.flags;
          if ((applicationFlags & ApplicationInfo.FLAG_SYSTEM) != ApplicationInfo.FLAG_SYSTEM) {
            iterator.remove();
          }
        }
      }
    }

    return resolveInfoList;
  }

  private List<ResolveInfo> queryImplicitIntent(Intent intent, int flags) {
    List<ResolveInfo> resolveInfoList = new ArrayList<>();

    for (Package appPackage : packages.values()) {

      for (Activity activity : appPackage.activities) {

        IntentFilter intentFilter = matchIntentFilter(intent, activity.intents, flags);
        if (intentFilter != null) {
          ResolveInfo resolveInfo = new ResolveInfo();
          resolveInfo.resolvePackageName = appPackage.packageName;
          resolveInfo.activityInfo = new ActivityInfo();
          resolveInfo.activityInfo.targetActivity = activity.info.targetActivity;
          resolveInfo.activityInfo.name = activity.info.name;
          resolveInfoList.add(resolveInfo);
        }
      }
    }

    return resolveInfoList;
  }

  @Implementation
  public int checkPermission(String permName, String pkgName) {
    PackageInfo permissionsInfo = packageInfos.get(pkgName);
    if (permissionsInfo == null || permissionsInfo.requestedPermissions == null) {
      return PackageManager.PERMISSION_DENIED;
    }
    for (String permission : permissionsInfo.requestedPermissions) {
      if (permission != null && permission.equals(permName)) {
        return PackageManager.PERMISSION_GRANTED;
      }
    }
    return PackageManager.PERMISSION_DENIED;
  }

  @Implementation
  public ActivityInfo getReceiverInfo(ComponentName className, int flags) throws NameNotFoundException {
    String packageName = className.getPackageName();

    PackageInfo packageInfo = packageInfos.get(packageName);
    for (ActivityInfo receiver : packageInfo.receivers) {
      if (resolvePackageName(packageName, className).equals(receiver.name)) {
        ActivityInfo result = new ActivityInfo();
        result.packageName = receiver.packageName;
        result.name = receiver.name;
        if ((flags & GET_META_DATA) != 0) {
          result.metaData = receiver.metaData;
        }
        return result;
      }
    }

    return null;
  }

  @Implementation
  public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags) {
    // Check the manually added resolve infos first.
    List<ResolveInfo> resolveInfos = queryIntent(intent, flags);
    if (!resolveInfos.isEmpty()) {
      return resolveInfos;
    }

    resolveInfos = new ArrayList<>();
    for (Package appPackage : packages.values()) {
      if (resolveInfos.isEmpty()) {
        for (Activity receiver : appPackage.receivers) {

          IntentFilter intentFilter = matchIntentFilter(intent, receiver.intents, flags);
          if (intentFilter != null) {
            resolveInfos.add(getResolveInfo(receiver, intentFilter));
          }
        }
      }
    }

    return resolveInfos;
  }

  private static IntentFilter matchIntentFilter(Intent intent, ArrayList<? extends PackageParser.IntentInfo> intentFilters, int flags) {
    for (PackageParser.IntentInfo intentInfo : intentFilters) {
      if (intentInfo.match(intent.getAction(), intent.getType(), intent.getScheme(), intent.getData(), intent.getCategories(), "ShadowPackageManager") >= 0) {
        return intentInfo;
      }
    }
    return null;
  }

  @Implementation
  public ResolveInfo resolveService(Intent intent, int flags) {
    List<ResolveInfo> candidates = queryIntentActivities(intent, flags);
    return candidates.isEmpty() ? null : candidates.get(0);
  }

  @Implementation
  public ServiceInfo getServiceInfo(ComponentName className, int flags) throws NameNotFoundException {
    String packageName = className.getPackageName();
    PackageInfo packageInfo = packageInfos.get(packageName);

    if (packageInfo != null) {
      String serviceName = className.getClassName();
      if (packageInfo.services != null) {
        for (ServiceInfo service : packageInfo.services) {
          if (serviceName.equals(service.name)) {
            ServiceInfo result = new ServiceInfo();
            result.packageName = service.packageName;
            result.name = service.name;
            result.applicationInfo = service.applicationInfo;
            result.permission = service.permission;
            if ((flags & GET_META_DATA) != 0) {
              result.metaData = service.metaData;
            }
            return result;
          }
        }
      }
      throw new NameNotFoundException(serviceName);
    }
    return null;
  }

  @Implementation
  public Resources getResourcesForApplication(@NonNull ApplicationInfo applicationInfo) throws PackageManager.NameNotFoundException {
    return getResourcesForApplication(applicationInfo.packageName);
  }

  @Implementation
  public List<ApplicationInfo> getInstalledApplications(int flags) {
    List<ApplicationInfo> result = new LinkedList<>();

    for (PackageInfo packageInfo : packageInfos.values()) {
      result.add(packageInfo.applicationInfo);
    }
    return result;
  }

  @Implementation
  public String getInstallerPackageName(String packageName) {
    return packageInstallerMap.get(packageName);
  }

  @Implementation
  public PermissionInfo getPermissionInfo(String name, int flags) throws NameNotFoundException {
    PermissionInfo permissionInfo = extraPermissions.get(name);
    if (permissionInfo != null) {
      return permissionInfo;
    }

    for (PackageInfo packageInfo : packageInfos.values()) {
      for (PermissionInfo permission : packageInfo.permissions) {
        if (name.equals(permission.name)) {
          return createCopyPermissionInfo(permission, flags);
        }
      }
    }

    throw new NameNotFoundException(name);
  }

  @Implementation(minSdk = M)
  public boolean shouldShowRequestPermissionRationale(String permission) {
    return permissionRationaleMap.containsKey(permission) ? permissionRationaleMap.get(permission) : false;
  }

  @Implementation
  public FeatureInfo[] getSystemAvailableFeatures() {
    return systemAvailableFeatures.isEmpty() ? null : systemAvailableFeatures.toArray(new FeatureInfo[systemAvailableFeatures.size()]);
  }

  @Implementation
  public void verifyPendingInstall(int id, int verificationCode) {
    if (verificationResults.containsKey(id)) {
      throw new IllegalStateException("Multiple verifications for id=" + id);
    }
    verificationResults.put(id, verificationCode);
  }

  @Implementation
  public void extendVerificationTimeout(int id, int verificationCodeAtTimeout, long millisecondsToDelay) {
    verificationTimeoutExtension.put(id, millisecondsToDelay);
  }

  @Implementation
  public void freeStorageAndNotify(long freeStorageSize, IPackageDataObserver observer) {
  }

  @Implementation
  public void freeStorageAndNotify(String volumeUuid, long freeStorageSize, IPackageDataObserver observer) {
  }

  @Implementation
  public void setInstallerPackageName(String targetPackage, String installerPackageName) {
    packageInstallerMap.put(targetPackage, installerPackageName);
  }

  @Implementation
  public List<ResolveInfo> queryIntentContentProviders(Intent intent, int flags) {
    return Collections.emptyList();
  }

  @Implementation
  public List<ResolveInfo> queryIntentContentProvidersAsUser(Intent intent, int flags, int userId) {
    return Collections.emptyList();
  }

  @Implementation
  public String getPermissionControllerPackageName() {
    return null;
  }

  @Implementation(maxSdk = JELLY_BEAN)
  public void getPackageSizeInfo(String packageName, final IPackageStatsObserver observer) {
    final PackageStats packageStats = packageStatsMap.get(packageName);
    new Handler(Looper.getMainLooper()).post(new Runnable() {

      public void run() {
        try {
          observer.onGetStatsCompleted(packageStats, packageStats != null);
        } catch (RemoteException remoteException) {
          remoteException.rethrowFromSystemServer();
        }
      }
    });
  }

  @Implementation(minSdk = JELLY_BEAN_MR1, maxSdk = M)
  public void getPackageSizeInfo(String pkgName, int uid, final IPackageStatsObserver callback) {
    final PackageStats packageStats = packageStatsMap.get(pkgName);
    new Handler(Looper.getMainLooper()).post(new Runnable() {

      public void run() {
        try {
          callback.onGetStatsCompleted(packageStats, packageStats != null);
        } catch (RemoteException remoteException) {
          remoteException.rethrowFromSystemServer();
        }
      }
    });
  }

  @Implementation(minSdk = N)
  public void getPackageSizeInfoAsUser(String pkgName, int uid, final IPackageStatsObserver callback) {
    final PackageStats packageStats = packageStatsMap.get(pkgName);
    new Handler(Looper.getMainLooper()).post(new Runnable() {

      public void run() {
        try {
          callback.onGetStatsCompleted(packageStats, packageStats != null);
        } catch (RemoteException remoteException) {
          remoteException.rethrowFromSystemServer();
        }
      }
    });
  }

  @Implementation
  public void deletePackage(String packageName, IPackageDeleteObserver observer, int flags) {
    pendingDeleteCallbacks.put(packageName, observer);
  }

  @Implementation
  public String[] currentToCanonicalPackageNames(String[] names) {
    String[] out = new String[names.length];
    for (int i = names.length - 1; i >= 0; i--) {
      if (currentToCanonicalNames.containsKey(names[i])) {
        out[i] = currentToCanonicalNames.get(names[i]);
      } else {
        out[i] = names[i];
      }
    }
    return out;
  }

  @Implementation
  public boolean isSafeMode() {
    return false;
  }

  @Implementation
  public Drawable getApplicationIcon(String packageName) throws NameNotFoundException {
    return applicationIcons.get(packageName);
  }

  @Implementation
  public Drawable getApplicationIcon(ApplicationInfo info) {
    return null;
  }

  @Implementation
  public Drawable getUserBadgeForDensity(UserHandle userHandle, int i) {
    return null;
  }

  @Implementation
  public int checkSignatures(String pkg1, String pkg2) {
    try {
      PackageInfo packageInfo1 = getPackageInfo(pkg1, GET_SIGNATURES);
      PackageInfo packageInfo2 = getPackageInfo(pkg2, GET_SIGNATURES);
      return compareSignature(packageInfo1.signatures, packageInfo2.signatures);
    } catch (NameNotFoundException e) {
      return SIGNATURE_UNKNOWN_PACKAGE;
    }
  }

  @Implementation
  public int checkSignatures(int uid1, int uid2) {
    return 0;
  }

  @Implementation
  public List<PermissionInfo> queryPermissionsByGroup(String group, int flags) throws NameNotFoundException {
    List<PermissionInfo> result = new LinkedList<>();
    for (PermissionInfo permissionInfo : extraPermissions.values()) {
      if (Objects.equals(permissionInfo.group, group)) {
        result.add(permissionInfo);
      }
    }

    for (PackageInfo packageInfo : packageInfos.values()) {
      for (PermissionInfo permission : packageInfo.permissions) {
        if (Objects.equals(group, permission.group)) {
          result.add(createCopyPermissionInfo(permission, flags));
        }
      }
    }

    return result;
  }

  private static PermissionInfo createCopyPermissionInfo(PermissionInfo src, int flags) {
    PermissionInfo matchedPermission = new PermissionInfo(src);
    if ((flags & GET_META_DATA) != GET_META_DATA) {
      matchedPermission.metaData = null;
    }
    return matchedPermission;
  }

  public CharSequence getApplicationLabel(ApplicationInfo info) {
    return info.name;
  }

  @Implementation
  public Intent getLaunchIntentForPackage(String packageName) {
    Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
    intentToResolve.addCategory(Intent.CATEGORY_INFO);
    intentToResolve.setPackage(packageName);
    List<ResolveInfo> ris = queryIntentActivities(intentToResolve, 0);

    if (ris == null || ris.isEmpty()) {
      intentToResolve.removeCategory(Intent.CATEGORY_INFO);
      intentToResolve.addCategory(Intent.CATEGORY_LAUNCHER);
      intentToResolve.setPackage(packageName);
      ris = queryIntentActivities(intentToResolve, 0);
    }
    if (ris == null || ris.isEmpty()) {
      return null;
    }
    Intent intent = new Intent(intentToResolve);
    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    intent.setClassName(ris.get(0).activityInfo.packageName, ris.get(0).activityInfo.name);
    return intent;
  }

  ////////////////////////////

  @Implementation
  public PackageInfo getPackageInfoAsUser(String packageName, int flags, int userId)
      throws NameNotFoundException {
    return null;
  }

  @Implementation
  public String[] canonicalToCurrentPackageNames(String[] names) {
    return new String[0];
  }

  @Implementation
  public Intent getLeanbackLaunchIntentForPackage(String packageName) {
    return null;
  }

  @Implementation
  public int[] getPackageGids(String packageName) throws NameNotFoundException {
    return new int[0];
  }

  @Implementation
  public int[] getPackageGids(String packageName, int flags) throws NameNotFoundException {
    return null;
  }

  @Implementation
  public int getPackageUid(String packageName, int flags) throws NameNotFoundException {
    Integer uid = uidForPackage.get(packageName);
    if (uid == null) {
      throw new NameNotFoundException(packageName);
    }
    return uid;
  }

  @Implementation
  public int getPackageUidAsUser(String packageName, int userId) throws NameNotFoundException {
    return 0;
  }

  @Implementation
  public int getPackageUidAsUser(String packageName, int flags, int userId)
      throws NameNotFoundException {
    return 0;
  }

  @Implementation
  public PermissionGroupInfo getPermissionGroupInfo(String name, int flags)
      throws NameNotFoundException {
    return null;
  }

  @Implementation
  public List<PermissionGroupInfo> getAllPermissionGroups(int flags) {
    return null;
  }

  @Implementation
  public ApplicationInfo getApplicationInfo(String packageName, int flags) throws NameNotFoundException {
    PackageInfo info = packageInfos.get(packageName);
    if (info != null) {
      try {
        PackageInfo packageInfo = getPackageInfo(packageName, -1);
      } catch (NameNotFoundException e) {
        throw new IllegalArgumentException(e);
      }

      if (applicationEnabledSettingMap.get(packageName) == COMPONENT_ENABLED_STATE_DISABLED
          && (flags & MATCH_UNINSTALLED_PACKAGES) != MATCH_UNINSTALLED_PACKAGES) {
        throw new NameNotFoundException("Package is disabled, can't find");
      }
      return info.applicationInfo;
    } else {
      throw new NameNotFoundException(packageName);
    }
  }
  
  @Implementation
  public String[] getSystemSharedLibraryNames() {
    return new String[0];
  }

  @Implementation
  public
  @NonNull
  String getServicesSystemSharedLibraryPackageName() {
    return null;
  }

  @Implementation
  public
  @NonNull
  String getSharedSystemSharedLibraryPackageName() {
    return "";
  }

  @Implementation
  public boolean hasSystemFeature(String name, int version) {
    return false;
  }

  @Implementation
  public boolean isPermissionRevokedByPolicy(String permName, String pkgName) {
    return false;
  }

  @Implementation
  public boolean addPermission(PermissionInfo info) {
    return false;
  }

  @Implementation
  public boolean addPermissionAsync(PermissionInfo info) {
    return false;
  }

  @Implementation
  public void removePermission(String name) {
  }

  @Implementation
  public void grantRuntimePermission(String packageName, String permissionName, UserHandle user) {
  }

  @Implementation
  public void revokeRuntimePermission(String packageName, String permissionName, UserHandle user) {
  }

  @Implementation
  public int getPermissionFlags(String permissionName, String packageName, UserHandle user) {
    return 0;
  }

  @Implementation
  public void updatePermissionFlags(String permissionName, String packageName, int flagMask,
      int flagValues, UserHandle user) {
  }

  @Implementation
  public int getUidForSharedUser(String sharedUserName) throws NameNotFoundException {
    return 0;
  }

  @Implementation
  public List<PackageInfo> getInstalledPackagesAsUser(int flags, int userId) {
    return null;
  }

  @Implementation
  public List<PackageInfo> getPackagesHoldingPermissions(String[] permissions, int flags) {
    return null;
  }

  @Implementation
  public ResolveInfo resolveActivityAsUser(Intent intent, int flags, int userId) {
    return null;
  }

  @Implementation
  public List<ResolveInfo> queryIntentActivitiesAsUser(Intent intent, int flags, int userId) {
    return null;
  }

  @Implementation
  public List<ResolveInfo> queryIntentActivityOptions(ComponentName caller, Intent[] specifics, Intent intent, int flags) {
    return null;
  }

  @Implementation
  public List<ResolveInfo> queryBroadcastReceiversAsUser(Intent intent, int flags, int userId) {
    return null;
  }

  @Implementation
  public List<ResolveInfo> queryIntentServicesAsUser(Intent intent, int flags, int userId) {
    return null;
  }

  @Implementation
  public List<ProviderInfo> queryContentProviders(String processName, int uid, int flags) {
    return null;
  }

  @Implementation
  public InstrumentationInfo getInstrumentationInfo(ComponentName className, int flags) throws NameNotFoundException {
    return null;
  }

  @Implementation
  public List<InstrumentationInfo> queryInstrumentation(String targetPackage, int flags) {
    return null;
  }

  @Override @Nullable
  @Implementation
  public Drawable getDrawable(String packageName, @DrawableRes int resId, @Nullable ApplicationInfo appInfo) {
    return drawables.get(new Pair(packageName, resId));
  }

  @Override @Implementation
  public Drawable getActivityIcon(ComponentName activityName) throws NameNotFoundException {
    return drawableList.get(activityName);
  }

  @Override public Drawable getActivityIcon(Intent intent) throws NameNotFoundException {
    return drawableList.get(intent.getComponent());
  }

  @Implementation
  public Drawable getDefaultActivityIcon() {
    return Resources.getSystem().getDrawable(com.android.internal.R.drawable.sym_def_app_icon);
  }

  @Implementation
  public Drawable getActivityBanner(ComponentName activityName) throws NameNotFoundException {
    return null;
  }

  @Implementation
  public Drawable getActivityBanner(Intent intent) throws NameNotFoundException {
    return null;
  }

  @Implementation
  public Drawable getApplicationBanner(ApplicationInfo info) {
    return null;
  }

  @Implementation
  public Drawable getApplicationBanner(String packageName) throws NameNotFoundException {
    return null;
  }

  @Implementation
  public Drawable getActivityLogo(ComponentName activityName) throws NameNotFoundException {
    return null;
  }

  @Implementation
  public Drawable getActivityLogo(Intent intent) throws NameNotFoundException {
    return null;
  }

  @Implementation
  public Drawable getApplicationLogo(ApplicationInfo info) {
    return null;
  }

  @Implementation
  public Drawable getApplicationLogo(String packageName) throws NameNotFoundException {
    return null;
  }

  @Implementation
  public Drawable getUserBadgedIcon(Drawable icon, UserHandle user) {
    return null;
  }

  @Implementation
  public Drawable getUserBadgedDrawableForDensity(Drawable drawable, UserHandle user, Rect badgeLocation, int badgeDensity) {
    return null;
  }

  @Implementation
  public Drawable getUserBadgeForDensityNoBackground(UserHandle user, int density) {
    return null;
  }

  @Implementation
  public CharSequence getUserBadgedLabel(CharSequence label, UserHandle user) {
    return null;
  }

  @Implementation
  public Resources getResourcesForActivity(ComponentName activityName) throws NameNotFoundException {
    return null;
  }

  @Implementation
  public Resources getResourcesForApplication(String appPackageName) throws NameNotFoundException {
    if (RuntimeEnvironment.application.getPackageName().equals(appPackageName)) {
      return RuntimeEnvironment.application.getResources();
    } else if (packageInfos.containsKey(appPackageName)) {
      Resources appResources = resources.get(appPackageName);
      if (appResources == null) {
        appResources = new Resources(new AssetManager(), null, null);
        resources.put(appPackageName, appResources);
      }
      return appResources;
    }
    throw new NameNotFoundException(appPackageName);
  }

  @Implementation
  public Resources getResourcesForApplicationAsUser(String appPackageName, int userId) throws NameNotFoundException {
    return null;
  }

  @Implementation
  public void addOnPermissionsChangeListener(Object listener) {
  }

  @Implementation
  public void removeOnPermissionsChangeListener(Object listener) {
  }

  @Implementation
  public void installPackage(Uri packageURI, IPackageInstallObserver observer, int flags, String installerPackageName) {
  }

  @Implementation
  public void installPackage(Object packageURI, Object observer, Object flags, Object installerPackageName) {
  }

  @Implementation
  public int installExistingPackage(String packageName) throws NameNotFoundException {
    return 0;
  }

  @Implementation
  public int installExistingPackageAsUser(String packageName, int userId) throws NameNotFoundException {
    return 0;
  }

  @Implementation
  public void verifyIntentFilter(int id, int verificationCode, List<String> failedDomains) {
  }

  @Implementation
  public int getIntentVerificationStatusAsUser(String packageName, int userId) {
    return 0;
  }

  @Implementation
  public boolean updateIntentVerificationStatusAsUser(String packageName, int status, int userId) {
    return false;
  }

  @Implementation
  public List<IntentFilterVerificationInfo> getIntentFilterVerifications(String packageName) {
    return null;
  }

  @Implementation
  public List<IntentFilter> getAllIntentFilters(String packageName) {
    return null;
  }

  @Implementation
  public String getDefaultBrowserPackageNameAsUser(int userId) {
    return null;
  }

  @Implementation
  public boolean setDefaultBrowserPackageNameAsUser(String packageName, int userId) {
    return false;
  }

  @Implementation
  public int getMoveStatus(int moveId) {
    return 0;
  }

  @Implementation
  public void registerMoveCallback(Object callback, Object handler) {
  }

  @Implementation
  public void unregisterMoveCallback(Object callback) {
  }

  @Implementation
  public Object movePackage(Object packageName, Object vol) {
    return 0;
  }

  @Implementation
  public Object getPackageCurrentVolume(Object app) {
    return null;
  }

  @Implementation
  public List<VolumeInfo> getPackageCandidateVolumes(ApplicationInfo app) {
    return null;
  }

  @Implementation
  public Object movePrimaryStorage(Object vol) {
    return 0;
  }

  @Implementation
  public @Nullable Object getPrimaryStorageCurrentVolume() {
    return null;
  }

  @Implementation
  public @NonNull List<VolumeInfo> getPrimaryStorageCandidateVolumes() {
    return null;
  }

  @Implementation
  public void deletePackageAsUser(String packageName, IPackageDeleteObserver observer, int flags, int userId) {
  }

  @Implementation
  public void clearApplicationUserData(String packageName, IPackageDataObserver observer) {
  }

  @Implementation
  public void deleteApplicationCacheFiles(String packageName, IPackageDataObserver observer) {
  }

  @Implementation
  public void deleteApplicationCacheFilesAsUser(String packageName, int userId, IPackageDataObserver observer) {
  }

  @Implementation
  public void freeStorage(String volumeUuid, long freeStorageSize, IntentSender pi) {
  }

  @Implementation
  public String[] setPackagesSuspendedAsUser(String[] packageNames, boolean suspended, int userId) {
    return null;
  }

  @Implementation
  public boolean isPackageSuspendedForUser(String packageName, int userId) {
    return false;
  }

  @Implementation
  public void addPackageToPreferred(String packageName) {
  }

  @Implementation
  public void removePackageFromPreferred(String packageName) {
  }

  @Implementation
  public List<PackageInfo> getPreferredPackages(int flags) {
    return null;
  }

  @Override public void addPreferredActivity(IntentFilter filter, int match, ComponentName[] set, ComponentName activity) {
    preferredActivities.put(filter, activity);
  }

  @Implementation
  public void replacePreferredActivity(IntentFilter filter, int match, ComponentName[] set, ComponentName activity) {
  }

  @Implementation
  public void clearPackagePreferredActivities(String packageName) {
  }

  @Override public int getPreferredActivities(List<IntentFilter> outFilters,
      List<ComponentName> outActivities, String packageName) {
    if (outFilters == null) {
      return 0;
    }

    Set<IntentFilter> filters = preferredActivities.keySet();
    for (IntentFilter filter : outFilters) {
      step:
      for (IntentFilter testFilter : filters) {
        ComponentName name = preferredActivities.get(testFilter);
        // filter out based on the given packageName;
        if (packageName != null && !name.getPackageName().equals(packageName)) {
          continue step;
        }

        // Check actions
        Iterator<String> iterator = filter.actionsIterator();
        while (iterator.hasNext()) {
          if (!testFilter.matchAction(iterator.next())) {
            continue step;
          }
        }

        iterator = filter.categoriesIterator();
        while (iterator.hasNext()) {
          if (!filter.hasCategory(iterator.next())) {
            continue step;
          }
        }

        if (outActivities == null) {
          outActivities = new ArrayList<>();
        }

        outActivities.add(name);
      }
    }

    return 0;
  }

  @Implementation
  public ComponentName getHomeActivities(List<ResolveInfo> outActivities) {
    return null;
  }

  @Implementation
  public void flushPackageRestrictionsAsUser(int userId) {
  }

  @Implementation
  public boolean setApplicationHiddenSettingAsUser(String packageName, boolean hidden, UserHandle user) {
    return false;
  }

  @Implementation
  public boolean getApplicationHiddenSettingAsUser(String packageName, UserHandle user) {
    return false;
  }

  @Implementation
  public Object getKeySetByAlias(String packageName, String alias) {
    return null;
  }

  @Implementation
  public Object getSigningKeySet(String packageName) {
    return null;
  }

  @Implementation
  public boolean isSignedBy(String packageName, Object ks) {
    return false;
  }

  @Implementation
  public boolean isSignedByExactly(String packageName, Object ks) {
    return false;
  }

  @Implementation
  public VerifierDeviceIdentity getVerifierDeviceIdentity() {
    return null;
  }

  @Implementation
  public boolean isUpgrade() {
    return false;
  }

  @Implementation
  public boolean isPackageAvailable(String packageName) {
    return false;
  }

  @Implementation
  public void addCrossProfileIntentFilter(IntentFilter filter, int sourceUserId, int targetUserId, int flags) {
  }

  @Implementation
  public void clearCrossProfileIntentFilters(int sourceUserId) {
  }

  @Implementation
  public Drawable loadItemIcon(PackageItemInfo itemInfo, ApplicationInfo appInfo) {
    return null;
  }

  @Implementation
  public Drawable loadUnbadgedItemIcon(PackageItemInfo itemInfo, ApplicationInfo appInfo) {
    return null;
  }
}
