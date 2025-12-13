/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import java.awt.AWTError;
import java.awt.EventQueue;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.TrayIcon.MessageType;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

import org.eclipse.jdt.annotation.Nullable;

import net.sf.jstuff.core.logging.Logger;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public final class DesktopNotifications {

   private static final Logger LOG = Logger.create();

   private static final @Nullable TrayIcon TRAY_ICON;
   private static final @Nullable String APP_ICON_BASE64;

   static {
      if (isSupported()) {
         final var trayIcon = TRAY_ICON = new TrayIcon(Toolkit.getDefaultToolkit().createImage(DesktopNotifications.class.getResource(
            "/copycat16x16.png")));
         trayIcon.setImageAutoSize(true);
         final var popup = new PopupMenu();
         popup.add("Copycat");
         popup.addSeparator();
         final var exitItem = new MenuItem("Exit (CTRL+C)");
         popup.add(exitItem);
         exitItem.addActionListener(e -> sun.misc.Signal.raise(new sun.misc.Signal("INT")));
         trayIcon.setPopupMenu(popup);
         EventQueue.invokeLater(() -> {
            try {
               SystemTray.getSystemTray().add(TRAY_ICON);
            } catch (final Exception ex) {
               LOG.warn(ex);
            }
         });
      } else {
         TRAY_ICON = null;
      }

      String iconBase64 = null;
      if (WindowsPowerShell.isAvailable()) {
         try (InputStream is = DesktopNotifications.class.getResourceAsStream("/copycat16x16.png")) {
            if (is != null) {
               final byte[] bytes = is.readAllBytes();
               iconBase64 = Base64.getEncoder().encodeToString(bytes);
            }
         } catch (final IOException ex) {
            LOG.warn(ex);
         }
      }
      APP_ICON_BASE64 = iconBase64;
   }

   public static void disposeTrayIcon() {
      if (TRAY_ICON == null)
         return;
      try {
         SystemTray.getSystemTray().remove(TRAY_ICON);
      } catch (final Exception ex) {
         LOG.debug(ex);
      }
   }

   private static boolean isRunningUnderJUnit() {
      for (final StackTraceElement e : Thread.currentThread().getStackTrace()) {
         final String cls = e.getClassName();
         if (cls.startsWith("org.junit."))
            return true;
      }
      return false;
   }

   private static boolean isSupported() {
      try {
         if (isRunningUnderJUnit())
            return false;
         return SystemTray.isSupported();
      } catch (final UnsatisfiedLinkError | AWTError ex) {
         // https://github.com/oracle/graal/issues/2842
         LOG.debug(ex);
         return false;
      }
   }

   public static boolean setTrayIconToolTip(final String message) {
      if (TRAY_ICON != null) {
         TRAY_ICON.setToolTip(message);
         return true;
      }
      return false;
   }

   /**
    * Shows the notification as popup and in the Windows notification center
    */
   public static synchronized boolean showSticky(final MessageType level, final String title, final String message) {
      if (WindowsPowerShell.isAvailable()) {
         try {
            WindowsPowerShell.executeAsync("" //
                  + "Add-Type -AssemblyName System.Windows.Forms;" //
                  + "Add-Type -AssemblyName System.Drawing;" //
                  + "$appIconStream=$null;" //
                  + "$appIcon=$null;" //
                  + "$msg=New-Object System.Windows.Forms.NotifyIcon;" //
                  + "try{" //
                  // https://learn.microsoft.com/en-us/dotnet/api/system.windows.forms.notifyicon?view=windowsdesktop-9.0#properties
                  + "$msg.BalloonTipTitle='[copycat] " + escapePowerShellSingleQuoted(title) + "';" //
                  + "$msg.BalloonTipText='" + escapePowerShellSingleQuoted(message) + "';" //
                  + "$msg.BalloonTipIcon='" + level + "';" // https://docs.microsoft.com/en-us/dotnet/api/system.windows.forms.tooltipicon
                  + (APP_ICON_BASE64 == null //
                        ? "$msg.Icon=[System.Drawing.SystemIcons]::Application;" //
                        : "$iconB64='" + APP_ICON_BASE64 + "';" //
                              + "$bytes=[Convert]::FromBase64String($iconB64);" //
                              + "$appIconStream=New-Object System.IO.MemoryStream(,$bytes);" //
                              + "$appIcon=[System.Drawing.Image]::FromStream($appIconStream);" //
                              + "$msg.Icon=[System.Drawing.Icon]::FromHandle($appIcon.GetHicon());" //
                  ) //
                  + "$msg.Visible=$True;" //
                  + "$msg.ShowBalloonTip(5000);" //
                  + "Start-Sleep -Milliseconds 5000;" //
                  + "}finally{" //
                  + "  try{$msg.Visible=$False;}catch{};" //
                  + "  try{$msg.Dispose();}catch{};" //
                  + "  if($appIcon){try{$appIcon.Dispose();}catch{}};" //
                  + "  if($appIconStream){try{$appIconStream.Dispose();}catch{}};" //
                  + "}" //
            );
            return true;
         } catch (final Exception ex) {
            LOG.warn(ex);
         }
      }

      return showTransient(level, title, message);
   }

   /**
    * Shows the notification as popup
    */
   public static synchronized boolean showTransient(final MessageType level, final String title, final String message) {
      if (TRAY_ICON != null) {
         TRAY_ICON.displayMessage("[copycat] " + title, message, level);
         return true;
      }
      if (WindowsPowerShell.isAvailable()) {
         try {
            WindowsPowerShell.executeAsync("" //
                  + "Add-Type -AssemblyName System.Windows.Forms;" //
                  + "Add-Type -AssemblyName System.Drawing;" //
                  + "$appIconStream=$null;" //
                  + "$appIcon=$null;" //
                  + "$msg=New-Object System.Windows.Forms.NotifyIcon;" //
                  + "try{" //
                  // https://learn.microsoft.com/en-us/dotnet/api/system.windows.forms.notifyicon?view=windowsdesktop-9.0#properties
                  + "$msg.BalloonTipTitle='[copycat] " + escapePowerShellSingleQuoted(title) + "';" //
                  + "$msg.BalloonTipText='" + escapePowerShellSingleQuoted(message) + "';" //
                  + "$msg.BalloonTipIcon='" + level + "';" // https://docs.microsoft.com/en-us/dotnet/api/system.windows.forms.tooltipicon
                  + (APP_ICON_BASE64 == null //
                        ? "$msg.Icon=[System.Drawing.SystemIcons]::Application;" //
                        : "$iconB64='" + APP_ICON_BASE64 + "';" //
                              + "$bytes=[Convert]::FromBase64String($iconB64);" //
                              + "$appIconStream=New-Object System.IO.MemoryStream(,$bytes);" //
                              + "$appIcon=[System.Drawing.Image]::FromStream($appIconStream);" //
                              + "$msg.Icon=[System.Drawing.Icon]::FromHandle($appIcon.GetHicon());" //
                  ) //
                  + "$msg.Visible=$True;" //
                  + "$msg.ShowBalloonTip(2000);" //
                  + "Start-Sleep -Milliseconds 2000;" //
                  + "}finally{" //
                  + "  $msg.Visible=$False;" //
                  + "  try{$msg.Dispose();}catch{};" //
                  + "  if($appIcon){try{$appIcon.Dispose();}catch{}};" //
                  + "  if($appIconStream){try{$appIconStream.Dispose();}catch{}};" //
                  + "}" //
            );
            return true;
         } catch (final Exception ex) {
            LOG.warn(ex);
         }
      }
      return false;
   }

   private static String escapePowerShellSingleQuoted(final String value) {
      // PowerShell single-quoted string escaping: represent a single quote as two single quotes.
      return value.replace("'", "''");
   }

   private DesktopNotifications() {
   }
}
