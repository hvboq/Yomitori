# Generates only the two public-domain, neutral test images beside this fixture.
# The committed PNG bytes are the Android input; Android's system fonts are not used.
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$assetDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../assets/native-quality'))
[IO.Directory]::CreateDirectory($assetDirectory) | Out-Null
$font = [Drawing.Font]::new('Meiryo', 72, [Drawing.FontStyle]::Regular, [Drawing.GraphicsUnit]::Pixel)
if ($font.FontFamily.Name -ne 'Meiryo') { throw 'The explicit Meiryo fixture font must be installed.' }
$format = [Drawing.StringFormat]::GenericTypographic.Clone()
$format.FormatFlags = $format.FormatFlags -bor [Drawing.StringFormatFlags]::NoClip -bor [Drawing.StringFormatFlags]::NoWrap
$black = [Drawing.SolidBrush]::new([Drawing.Color]::Black)
$white = [Drawing.SolidBrush]::new([Drawing.Color]::White)
$blue = [Drawing.SolidBrush]::new([Drawing.Color]::Blue)
$outline = [Drawing.Pen]::new([Drawing.Color]::FromArgb(255, 48, 48, 48), 6)
# Reading order is top-to-bottom within a column and right-to-left within each balloon.
# U+FE41/FE42/FE11/FE12 are the font's real vertical punctuation glyphs. Their semantic
# ground truth remains U+300C/U+300D/U+3001/U+3002; no model output defines this truth.
$columns = @(
    @{ balloon = 'left'; x = 430; y = 280; expected = '少し待って、' },
    @{ balloon = 'left'; x = 310; y = 280; expected = 'ください。' },
    @{ balloon = 'right'; x = 1000; y = 280; expected = '「明日もよろしく' },
    @{ balloon = 'right'; x = 880; y = 280; expected = 'お願いします。」' }
)
$vertical = @{ '「' = [char]0xFE41; '」' = [char]0xFE42; '、' = [char]0xFE11; '。' = [char]0xFE12 }
$files = @()
try {
    foreach ($withText in @($false, $true)) {
        $bitmap = [Drawing.Bitmap]::new(1200, 1600, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.Clear([Drawing.Color]::FromArgb(255, 192, 192, 192))
            $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
            $graphics.TextRenderingHint = [Drawing.Text.TextRenderingHint]::AntiAliasGridFit
            foreach ($left in @(80, 650)) {
                $path = [Drawing.Drawing2D.GraphicsPath]::new()
                try {
                    $path.AddArc($left, 100, 100, 100, 180, 90)
                    $path.AddArc($left + 370, 100, 100, 100, 270, 90)
                    $path.AddArc($left + 370, 1370, 100, 100, 0, 90)
                    $path.AddArc($left, 1370, 100, 100, 90, 90)
                    $path.CloseFigure()
                    $graphics.FillPath($white, $path)
                    $graphics.DrawPath($outline, $path)
                } finally { $path.Dispose() }
            }
            # This non-text colored mark must remain byte-for-pixel unchanged.
            $graphics.FillRectangle($blue, 120, 1240, 100, 100)
            if ($withText) {
                foreach ($column in $columns) {
                    for ($index = 0; $index -lt $column.expected.Length; $index++) {
                        $character = $column.expected.Substring($index, 1)
                        $glyph = if ($vertical.ContainsKey($character)) { [string]$vertical[$character] } else { $character }
                        $size = $graphics.MeasureString($glyph, $font, [Drawing.PointF]::Empty, $format)
                        $point = [Drawing.PointF]::new($column.x - $size.Width / 2, $column.y + $index * 78)
                        $graphics.DrawString($glyph, $font, $black, $point, $format)
                    }
                }
            }
            $name = if ($withText) { 'vertical-punctuation.png' } else { 'vertical-punctuation-clean.png' }
            $file = Join-Path $assetDirectory $name
            $bitmap.Save($file, [Drawing.Imaging.ImageFormat]::Png)
            $files += [ordered]@{ name = $name; sha256 = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() }
        } finally {
            $graphics.Dispose()
            $bitmap.Dispose()
        }
    }
    $manifest = [ordered]@{
        license = 'CC0-1.0; original neutral synthetic fixture generated for this test'
        width = 1200; height = 1600; font = 'Meiryo Regular'; fontSizePx = 72; cellHeightPx = 78
        fontFileSha256 = (Get-FileHash -LiteralPath (Join-Path $env:WINDIR 'Fonts/meiryo.ttc') -Algorithm SHA256).Hash.ToLowerInvariant()
        punctuation = 'Real vertical presentation glyphs FE41/FE42/FE11/FE12; semantic ground truth is 「 」 、 。'
        columns = $columns; files = $files
        expectedLeft = '少し待って、ください。'; expectedRight = '「明日もよろしくお願いします。」'
    }
    $json = ConvertTo-Json -InputObject $manifest -Depth 8
    [IO.File]::WriteAllText((Join-Path $assetDirectory 'vertical-punctuation.json'), $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    $files | ConvertTo-Json
} finally {
    $outline.Dispose(); $blue.Dispose(); $white.Dispose(); $black.Dispose(); $format.Dispose(); $font.Dispose()
}
