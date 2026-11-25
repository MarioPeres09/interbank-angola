Option Explicit

Dim shell, files, base, cmd, logPath
Set shell = CreateObject("WScript.Shell")
Set files = CreateObject("Scripting.FileSystemObject")
base = files.GetParentFolderName(WScript.ScriptFullName)
logPath = files.BuildPath(base, ".interbank-control.log")
cmd = """" & files.BuildPath(base, "controlo.cmd") & """"
shell.Run "%ComSpec% /d /c """ & cmd & " > """ & logPath & """ 2>&1""", 0, False
