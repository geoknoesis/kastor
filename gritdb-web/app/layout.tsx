import "./globals.css";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Grit DB",
  description: "The durable graph-native database for knowledge-heavy apps."
};

export default function RootLayout({
  children
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}

