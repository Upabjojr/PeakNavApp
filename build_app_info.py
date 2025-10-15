from pathlib import Path
import markdown


html_template = """
<!DOCTYPE html>
<html>
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
