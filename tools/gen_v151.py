import io

src = '/root/build_v15.sh'
dst = '/root/build_v151.sh'

s = io.open(src, encoding='utf-8').read()

# 版本号：1.5 -> 1.5.1，versionCode 6 -> 7
s = s.replace('--version-code 6', '--version-code 7')
s = s.replace('--version-name "1.5"', '--version-name "1.5.1"')
s = s.replace('rcb-v1.5.apk', 'rcb-v1.5.1.apk')
s = s.replace('build_v15.sh', 'build_v151.sh')

# 把新文件加进 kotlinc 源列表（紧跟在 TileService.kt 后面）
if 'RcbPanelActivity.kt' not in s:
    s = s.replace('TileService.kt', 'TileService.kt RcbPanelActivity.kt', 1)

io.open(dst, 'w', encoding='utf-8').write(s)
print('written', dst)
