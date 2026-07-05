# logbook-to-excel

logbook-to-excel is a small Java desktop utility that reads a Logbook log file and exports the parsed data into an Excel `.xlsx` file.

## Requirements

- JDK 17+ or JDK 21+
- Maven
- Windows for building the `.exe` / app image with `jpackage`

## Project build output

After running Maven, the JAR is expected to be generated in the `target` folder.

Example:

```text
target/logbook-to-excel.jar
```

## Build steps

### 1. Build the JAR

Run:

```bat
mvn clean package
```

This creates the application JAR in the `target` folder.

### 2. Prepare the jpackage input folder

Create a separate folder for `jpackage` input, for example:

```text
jpackage-input
```

Copy the generated JAR into that folder:

```text
jpackage-input/logbook-to-excel.jar
```

### 3. Run jpackage

From the project root, run:

```bat
jpackage ^
  --type app-image ^
  --name logbook-to-excel ^
  --app-version 1.0.0 ^
  --input jpackage-input ^
  --main-jar logbook-to-excel.jar ^
  --main-class com.example.LogbookToExcelApp ^
  --dest jpackage-dest ^
  --win-console
```

### 4. Run the generated app

After packaging completes, the output will be placed in:

```text
jpackage-dest/logbook-to-excel
```

Inside that folder, run:

```text
logbook-to-excel.exe
```

## Notes

- Do not point `--input` to the same folder used for `--dest`.
- If you change the main class package name, update the `--main-class` value.
- If you want to redistribute the app, zip the entire `jpackage-dest/logbook-to-excel` folder, not only the `.exe`.

## How it works

1. Select a log file.
2. The program parses the Logbook data.
3. The program generates an Excel file in the same folder as the selected log file.
4. A success or error message is shown to the user.