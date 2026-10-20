# hero 的生成方式

`../hero.webp` 是发布用图，`../hero.png` 是同一张的 PNG 备份。

- `hero-layout.html` — 版式源文件（1200×380），标题、副标题、元数据、卡片边框都在这里
- `hero-subject.png` — 位图主体（角色头像），从 `anime.png` 裁切而来

## 重新生成

```powershell
# 1. 用 Edge/Chrome headless 渲染 HTML，2 倍尺寸
& "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" `
  --headless=new --disable-gpu --hide-scrollbars `
  --force-device-scale-factor=2 --window-size=1200,380 `
  --screenshot="hero@2x.png" "file:///<本目录>/hero-layout.html"

# 2. 缩到 1200×380 并导出 WebP
python -c "from PIL import Image; im=Image.open('hero@2x.png').resize((1200,380), Image.LANCZOS); im.save('hero.webp', quality=88, method=6)"
```

改文案只需要改 `hero-layout.html` 里的文字；改头像只需要替换 `hero-subject.png`。
不要用图像模型生成标题文字——标题必须保持矢量级的清晰。
