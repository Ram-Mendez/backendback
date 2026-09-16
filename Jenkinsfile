pipeline {
    agent any

    tools {
        nodejs 'nodejs'
    }

    environment {
        CHROME_BIN = '/usr/bin/chromium'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Backend Test') {
            steps {
                sh 'chmod +x mvnw'
                sh './mvnw test'
            }
        }

        stage('Backend Package') {
            steps {
                sh './mvnw package -DskipTests'
            }
        }

        stage('Frontend Install') {
            steps {
                dir('frontend') {
                    sh 'node -v'
                    sh 'npm -v'
                    sh 'npm ci'
                }
            }
        }

        stage('Frontend Test') {
            steps {
                dir('frontend') {
                    sh 'npm test -- --watch=false'
                }
            }
        }

        stage('Frontend Build') {
            steps {
                dir('frontend') {
                    sh 'npm run build'
                }
            }
        }

        stage('Package Frontend') {
            steps {
                sh '''
                    rm -f frontend/ram-frontend.zip
                    jar --create \
                        --file frontend/ram-frontend.zip \
                        --no-manifest \
                        -C frontend/dist/ram-frontend .
                '''
            }
        }

        stage('Archive Artifacts') {
            steps {
                archiveArtifacts artifacts: 'target/*.jar, frontend/ram-frontend.zip',
                                 fingerprint: true
            }
        }
    }
}