import './globals.css';
import React from 'react';
import { AppShell } from '@/components/AppShell';

export const metadata = {
  title: 'OmniFace AI | Enterprise Web Dashboard',
  description: 'Enterprise Face Attendance Fleet & Member Management Console',
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body>
        <AppShell>{children}</AppShell>
      </body>
    </html>
  );
}
