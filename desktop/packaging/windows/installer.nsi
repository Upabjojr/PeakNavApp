; PeakNav Windows installer.
;
; Wraps the self-contained application image construo produces (the app jar, the
; native libraries and a bundled JRE 17) into a single setup executable. NSIS is
; used rather than jpackage because jpackage only emits an installer for the OS it
; runs on, and this project is built on Linux; makensis cross-compiles.
;
; Driven entirely by defines passed on the command line, so nothing here has to be
; edited when the version changes:
;
;   makensis -DAPP_SOURCE=... -DAPP_VERSION=... -DOUT_FILE=... installer.nsi

Unicode true
SetCompressor /SOLID lzma

!include "MUI2.nsh"
!include "FileFunc.nsh"
!include "LogicLib.nsh"

!define APP_NAME     "PeakNav"
!define PUBLISHER    "Francesco Bonazzi"
; The executable inside the application image is named after the Gradle `appName`
; property, which is lower case; the shortcuts and the entry in Apps & features
; use the capitalised name above.
!ifndef APP_EXE
    !define APP_EXE "peaknav.exe"
!endif
!define UNINST_KEY   "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_NAME}"

Name "${APP_NAME} ${APP_VERSION}"
OutFile "${OUT_FILE}"

; Per-user, under LOCALAPPDATA rather than Program Files. A machine-wide install
; needs administrator rights, and UAC's elevation prompt for an *unsigned*
; installer is the frightening red one; per-user keeps the friendlier dialog and
; needs no admin account at all. It also matches where the app already keeps its
; data (%APPDATA%\PeakNav, see DesktopFiles.getGdxFilesExternalPath).
RequestExecutionLevel user
InstallDir "$LOCALAPPDATA\Programs\${APP_NAME}"
InstallDirRegKey HKCU "Software\${APP_NAME}" "InstallDir"

VIProductVersion "${APP_VERSION}.0"
VIAddVersionKey "ProductName"     "${APP_NAME}"
VIAddVersionKey "FileDescription" "${APP_NAME} installer"
VIAddVersionKey "FileVersion"     "${APP_VERSION}"
VIAddVersionKey "ProductVersion"  "${APP_VERSION}"
VIAddVersionKey "CompanyName"     "${PUBLISHER}"
VIAddVersionKey "LegalCopyright"  "${PUBLISHER}"

!define MUI_ICON   "${APP_ICON}"
!define MUI_UNICON "${APP_ICON}"
!define MUI_ABORTWARNING

!insertmacro MUI_PAGE_LICENSE "${APP_LICENSE}"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_RUN "$INSTDIR\${APP_EXE}"
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "English"
!insertmacro MUI_LANGUAGE "Italian"

Section "Install"
    SetOutPath "$INSTDIR"
    ; /r over the whole application image: the JRE alone is several thousand
    ; files, so listing them is not an option. The pattern is "*" rather than the
    ; more common "*.*" because the JRE ships extension-less files, and forward
    ; slashes because this is compiled by makensis on Linux.
    File /r "${APP_SOURCE}/*"

    CreateShortcut "$SMPROGRAMS\${APP_NAME}.lnk" "$INSTDIR\${APP_EXE}" "" "$INSTDIR\${APP_EXE}" 0
    CreateShortcut "$DESKTOP\${APP_NAME}.lnk"    "$INSTDIR\${APP_EXE}" "" "$INSTDIR\${APP_EXE}" 0

    WriteUninstaller "$INSTDIR\Uninstall.exe"
    WriteRegStr HKCU "Software\${APP_NAME}" "InstallDir" "$INSTDIR"

    ; Registering here (rather than under HKLM) is what puts the app in
    ; "Apps & features" for this user without needing administrator rights.
    WriteRegStr   HKCU "${UNINST_KEY}" "DisplayName"     "${APP_NAME}"
    WriteRegStr   HKCU "${UNINST_KEY}" "DisplayVersion"  "${APP_VERSION}"
    WriteRegStr   HKCU "${UNINST_KEY}" "DisplayIcon"     "$INSTDIR\${APP_EXE}"
    WriteRegStr   HKCU "${UNINST_KEY}" "Publisher"       "${PUBLISHER}"
    WriteRegStr   HKCU "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
    WriteRegStr   HKCU "${UNINST_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
    WriteRegDWORD HKCU "${UNINST_KEY}" "NoModify" 1
    WriteRegDWORD HKCU "${UNINST_KEY}" "NoRepair" 1

    ; The size shown in Apps & features, in KB. Measured rather than hardcoded so
    ; it stays right as the bundled assets grow.
    ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
    IntFmt $0 "0x%08X" $0
    WriteRegDWORD HKCU "${UNINST_KEY}" "EstimatedSize" "$0"
SectionEnd

Section "Uninstall"
    Delete "$SMPROGRAMS\${APP_NAME}.lnk"
    Delete "$DESKTOP\${APP_NAME}.lnk"

    RMDir /r "$INSTDIR"

    DeleteRegKey HKCU "${UNINST_KEY}"
    DeleteRegKey HKCU "Software\${APP_NAME}"

    ; Downloaded map and elevation data lives in %APPDATA%\PeakNav and can run to
    ; many gigabytes that cost real time to fetch again. Removing it silently
    ; would be the wrong default for someone merely reinstalling, so ask, and
    ; treat "no" as the answer if the uninstaller is running unattended (/S).
    IfSilent skip_userdata
    MessageBox MB_YESNO|MB_ICONQUESTION|MB_DEFBUTTON2 \
        "Also delete downloaded maps, elevation data and settings?$\r$\n$\r$\n\
         These live in $APPDATA\${APP_NAME} and will have to be downloaded again.$\r$\n\
         Choose No if you are reinstalling ${APP_NAME}." \
        IDNO skip_userdata
    RMDir /r "$APPDATA\${APP_NAME}"
    skip_userdata:
SectionEnd
