/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.chen.email.provider;

import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;

import com.chen.email.NotificationController;
import com.chen.email.R;
import com.chen.email.service.EmailServiceUtils;
import com.chen.emailcommon.Logging;
import com.chen.emailcommon.provider.Account;
import com.chen.emailcommon.provider.HostAuth;
import com.chen.mail.utils.LogUtils;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AccountReconciler {
    /**
     * Get all AccountManager accounts for all email types.
     * @param context Our {@link Context}.
     * @return A list of all {@link android.accounts.Account}s created by our app.
     */
    private static List<android.accounts.Account> getAllAmAccounts(final Context context) {
        final AccountManager am = AccountManager.get(context);
        final ImmutableList.Builder<android.accounts.Account> builder = ImmutableList.builder();
        // TODO: Consider getting the types programmatically, in case we add more types.
        builder.addAll(Arrays.asList(am.getAccountsByType(
                context.getString(R.string.account_manager_type_legacy_imap))));
        builder.addAll(Arrays.asList(am.getAccountsByType(
                context.getString(R.string.account_manager_type_pop3))));
        builder.addAll(Arrays.asList(am.getAccountsByType(
                context.getString(R.string.account_manager_type_exchange))));
        return builder.build();
    }

    /**
     * Get a all {@link Account} objects from the {@link EmailProvider}.
     * @param context Our {@link Context}.
     * @return A list of all {@link Account}s from the {@link EmailProvider}.
     */
    private static List<Account> getAllEmailProviderAccounts(final Context context) {
        final Cursor c = context.getContentResolver().query(Account.CONTENT_URI,
                Account.CONTENT_PROJECTION, null, null, null);
        if (c == null) {
            return Collections.emptyList();
        }

        final ImmutableList.Builder<Account> builder = ImmutableList.builder();
        try {
            while (c.moveToNext()) {
                final Account account = new Account();
                account.restore(c);
                builder.add(account);
            }
        } finally {
            c.close();
        }
        return builder.build();
    }

    /**
     * Compare our account list (obtained from EmailProvider) with the account list owned by
     * AccountManager.  If there are any orphans (an account in one list without a corresponding
     * account in the other list), delete the orphan, as these must remain in sync.
     *
     * Note that the duplication of account information is caused by the Email application's
     * incomplete integration with AccountManager.
     *
     * This function may not be called from the main/UI thread, because it makes blocking calls
     * into the account manager.
     *
     * @param context The context in which to operate
     */
    public static void reconcileAccounts(final Context context) {
        final List<android.accounts.Account> amAccounts = getAllAmAccounts(context);
        final List<Account> providerAccounts = getAllEmailProviderAccounts(context);
        reconcileAccountsInternal(context, providerAccounts, amAccounts, true);
    }

    /**
     * Check if the AccountManager accounts list contains a specific account.
     * @param accounts The list of {@link android.accounts.Account} objects.
     * @param name The name of the account to find.
     * @return Whether the account is in the list.
     */
    private static boolean hasAmAccount(final List<android.accounts.Account> accounts,
            final String name, final String type) {
        for (final android.accounts.Account account : accounts) {
            if (account.name.equalsIgnoreCase(name) && account.type.equalsIgnoreCase(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if the EmailProvider accounts list contains a specific account.
     * @param accounts The list of {@link Account} objects.
     * @param name The name of the account to find.
     * @return Whether the account is in the list.
     */
    private static boolean hasEpAccount(final List<Account> accounts, final String name) {
        for (final Account account : accounts) {
            if (account.mEmailAddress.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Internal method to actually perform reconciliation, or simply check that it needs to be done
     * and avoid doing any heavy work, depending on the value of the passed in
     * {@code performReconciliation}.
     */
    private static boolean reconcileAccountsInternal(
            final Context context,
            final List<Account> emailProviderAccounts,
            final List<android.accounts.Account> accountManagerAccounts,
            final boolean performReconciliation) {
        boolean needsReconciling = false;
        boolean accountDeleted = false;
        boolean exchangeAccountDeleted = false;

        LogUtils.d(Logging.LOG_TAG, "reconcileAccountsInternal");

        // First, look through our EmailProvider accounts to make sure there's a corresponding
        // AccountManager account
        for (final Account providerAccount : emailProviderAccounts) {
            final String providerAccountName = providerAccount.mEmailAddress;
            final String providerAccountType =
                    EmailServiceUtils.getServiceInfoForAccount(context, providerAccount.mId)
                            .accountType;
            if (!hasAmAccount(accountManagerAccounts, providerAccountName, providerAccountType)) {
                if ((providerAccount.mFlags & Account.FLAGS_INCOMPLETE) != 0) {
                    LogUtils.w(Logging.LOG_TAG,
                            "Account reconciler noticed incomplete account; ignoring");
                    continue;
                }

                needsReconciling = true;
                if (performReconciliation) {
                    // This account has been deleted in the AccountManager!
                    LogUtils.d(Logging.LOG_TAG,
                            "Account deleted in AccountManager; deleting from provider: " +
                            providerAccountName);
                    // See if this is an exchange account
                    final HostAuth auth = providerAccount.getOrCreateHostAuthRecv(context);
                    LogUtils.d(Logging.LOG_TAG, "deleted account with hostAuth " + auth);
                    if (auth != null && TextUtils.equals(auth.mProtocol,
                            context.getString(R.string.protocol_eas))) {
                        exchangeAccountDeleted = true;
                    }
                    // Cancel all notifications for this account
                    NotificationController.cancelNotifications(context, providerAccount);

                    context.getContentResolver().delete(
                            EmailProvider.uiUri("uiaccount", providerAccount.mId), null, null);

                    accountDeleted = true;

                }
            }
        }
        // Now, look through AccountManager accounts to make sure we have a corresponding cached EAS
        // account from EmailProvider
        for (final android.accounts.Account accountManagerAccount : accountManagerAccounts) {
            final String accountManagerAccountName = accountManagerAccount.name;
            if (!hasEpAccount(emailProviderAccounts, accountManagerAccountName)) {
                // This account has been deleted from the EmailProvider database
                needsReconciling = true;

                if (performReconciliation) {
                    LogUtils.d(Logging.LOG_TAG,
                            "Account deleted from provider; deleting from AccountManager: " +
                            accountManagerAccountName);
                    // Delete the account
                    AccountManagerFuture<Boolean> blockingResult = AccountManager.get(context)
                            .removeAccount(accountManagerAccount, null, null);
                    try {
                        // Note: All of the potential errors from removeAccount() are simply logged
                        // here, as there is nothing to actually do about them.
                        blockingResult.getResult();
                    } catch (OperationCanceledException e) {
                        LogUtils.w(Logging.LOG_TAG, e.toString());
                    } catch (AuthenticatorException e) {
                        LogUtils.w(Logging.LOG_TAG, e.toString());
                    } catch (IOException e) {
                        LogUtils.w(Logging.LOG_TAG, e.toString());
                    }
                }
            }
        }

        // If an account has been deleted, the simplest thing is just to kill our process.
        // Otherwise we might have a service running trying to do something for the account
        // which has been deleted, which can get NPEs. It's not as clean is it could be, but
        // it still works pretty well because there is nowhere in the email app to delete the
        // account. You have to go to Settings, so it's not user visible that the Email app
        // has been killed.
        if (accountDeleted) {
            LogUtils.i(Logging.LOG_TAG, "Restarting because account deleted");
            if (exchangeAccountDeleted) {
                EmailServiceUtils.killService(context, context.getString(R.string.protocol_eas));
            }
            System.exit(-1);
        }

        return needsReconciling;
    }
}
