#!/system/bin/sh
# NEW_TASK | CLEAR_TASK 只重建本模块的设置任务，清掉上次打开的弹窗。
exec /system/bin/am start --user 0 -f 0x10008000 -n local.cmcc.usage/.SetupActivity
