; ==============================================================================
; MARG ERP PHARMA QUICK-ACTION KEY TRANSLATOR (WINDOWS 10 & 11)
; For 60% compact keyboards, tablets, and devices without physical F1-F12 keys.
; ==============================================================================
#NoEnv
#SingleInstance force
SendMode Input
SetWorkingDir %A_ScriptDir%

TrayTip, Marg Pharma Keys Active, Alt+2=Date | Alt+3=Item | Alt+7=Cash | Alt+9=Print | Alt+0=Save, 5, 1

; Alt + Numbers mapping to F1 - F12
!1::Send {F1}
!2::Send {F2}       ; Date Change
!3::Send {F3}       ; Item Master / Search
!4::Send {F4}
!5::Send {F5}
!6::Send {F6}
!7::Send {F7}       ; Payment Mode (Cash / Credit)
!8::Send {F8}       ; Bill Modify / Edit
!9::Send {F9}       ; Print Bill
!0::Send {F10}      ; Save Bill
!-::Send {F11}
!=::Send {F12}
!e::Send {Escape}   ; Cancel / Exit

; Standard Ctrl Shortcuts commonly used in Billing
^p::Send {F9}       ; Ctrl+P -> Print Bill
^s::Send {F10}      ; Ctrl+S -> Save Bill
