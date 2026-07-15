if (process.env.CI === "true") {
    config.set({
        browsers: ["ChromeHeadlessNoSandbox"],
        customLaunchers: {
            ChromeHeadlessNoSandbox: {
                base: "ChromeHeadless",
                flags: [
                    "--no-sandbox",
                    "--disable-dev-shm-usage",
                ],
            },
        },
    });
}
