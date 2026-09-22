# 本库对下面几个库是 compileOnly：只有 LiquidGlassDialogBuilder（appcompat + material）和
# LiquidGlassTabLayoutMediator（viewpager2 + recyclerview）引用它们，不用这两个类的应用不必引入。
# 应用没引入时 R8 会把这些引用当成缺类报错，这里压掉。
-dontwarn androidx.appcompat.**
-dontwarn com.google.android.material.**
-dontwarn androidx.viewpager2.**
-dontwarn androidx.recyclerview.**
