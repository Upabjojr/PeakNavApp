from pathlib import Path
import markdown


# Every HTML page below is generated from a Markdown source and must never be edited
# by hand - see CLAUDE.md.  The styling therefore lives here, in the one template, so
# that a change survives the next regeneration.
html_template = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
body {{ margin: 0 auto; padding: 1rem; max-width: 44rem;
       font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
       font-size: 1rem; line-height: 1.6; overflow-wrap: break-word; }}
h1 {{ font-size: 1.5rem; }}
h2 {{ font-size: 1.2rem; margin-top: 2rem; }}
h3 {{ font-size: 1.05rem; }}
/* The license texts reproduced below are pre-formatted; wrap them so they do not force
   horizontal scrolling on a phone screen. */
pre {{ white-space: pre-wrap; word-wrap: break-word; font-size: 0.85em; }}
a {{ overflow-wrap: anywhere; }}
</style>
</head>
<body>
{body}
</body>
</html>
"""

privacy = open("privacy_statement.md", "r").read()

privacy_html = markdown.markdown(privacy)

with open("privacy_statement.html", "w", encoding="utf-8", newline="\n") as fout:
    fout.write(html_template.format(body=privacy_html))

license_file = "# License\n\n" + open("LICENSE", "r").read()

license_html = markdown.markdown(license_file)

with open("LICENSE.html", "w", encoding="utf-8", newline="\n") as fout:
    fout.write(html_template.format(body=license_html))

third_party_licenses = open("THIRD_PARTY_LICENSES.md", "r").read()

third_party_licenses_html = markdown.markdown(third_party_licenses)

with open("THIRD_PARTY_LICENSES.html", "w", encoding="utf-8", newline="\n") as fout:
    fout.write(html_template.format(body=third_party_licenses_html))

app_info_body = markdown.markdown(license_file + "\n\n" + privacy + "\n\n" + third_party_licenses)

app_info = html_template.format(body=app_info_body)

folder_path = Path(__file__, "..").resolve().absolute()
app_info_assets = folder_path / "assets" / "info" / "app_info.html"

app_info_assets.open("w", encoding="utf-8", newline="\n").write(app_info)
