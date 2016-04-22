# query
mainly in SearchActivity.java

git clone to local path
open AS

-> import Android Studio Project

in app/build.gradle reference: useLibrary 'org.apache.http.legacy'
so you have to add a file to path: <sdk-path>\platforms\android-23\optional\
named : optional.json
with follow content:
[
  {
    "name": "org.apache.http.legacy",
    "jar": "org.apache.http.legacy.jar",
    "manifest": false
  }
]


solution from :
http://stackoverflow.com/questions/33898857/why-warningunable-to-find-optional-library-org-apache-http-legacy-occurs

that's ok