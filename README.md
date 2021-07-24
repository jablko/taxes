# taxes

1. Clone: `git clone https://github.com/jablko/taxes.git`
2. (optional) Create a new branch: `git switch --create private`
3. :construction:
4. (optional) Commit your electronic receipts, scanned supporting documents,
   etc. to `<year>/<subdirectory-for-the-return>`. They are ignored by the
   software; this is for your own record keeping only.
5. Recalculate: `make` or
   `./gradlew :calc:run --args <year>/<subdirectory-for-the-return>/<form>`
