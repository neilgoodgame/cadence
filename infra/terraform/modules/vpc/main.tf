########################################################################
# VPC - the network Cadence's AWS resources live in. Standard shape:
# public subnets for internet-facing things (ALB, NAT Gateway), private
# subnets for everything else (RDS, ECS tasks) - see infra/README.md
# Step 2 for the full explanation.
########################################################################

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, var.az_count)

  # /24s carved out of the /16: public subnets start at .0, private
  # subnets start at .10 - purely a naming convenience so the two groups
  # are visually distinguishable in the console, not a technical
  # requirement.
  public_subnet_cidrs  = [for i in range(var.az_count) : cidrsubnet(var.vpc_cidr, 8, i)]
  private_subnet_cidrs = [for i in range(var.az_count) : cidrsubnet(var.vpc_cidr, 8, i + 10)]

  nat_gateway_count = var.single_nat_gateway ? 1 : var.az_count
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = merge(var.tags, { Name = "cadence-${var.environment}-vpc" })
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = "cadence-${var.environment}-igw" })
}

# ---- public subnets: ALB, NAT Gateway ----

resource "aws_subnet" "public" {
  count                   = var.az_count
  vpc_id                  = aws_vpc.this.id
  cidr_block              = local.public_subnet_cidrs[count.index]
  availability_zone       = local.azs[count.index]
  map_public_ip_on_launch = true

  tags = merge(var.tags, { Name = "cadence-${var.environment}-public-${local.azs[count.index]}" })
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = "cadence-${var.environment}-public-rt" })
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id              = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  count          = var.az_count
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# ---- NAT Gateway(s): lets private subnets reach the internet outbound
# (OAuth callbacks, webhook delivery, pulling container images) without
# being reachable from it ----

resource "aws_eip" "nat" {
  count  = local.nat_gateway_count
  domain = "vpc"
  tags   = merge(var.tags, { Name = "cadence-${var.environment}-nat-eip-${count.index}" })
}

resource "aws_nat_gateway" "this" {
  count         = local.nat_gateway_count
  allocation_id = aws_eip.nat[count.index].id
  # A NAT Gateway must live in a PUBLIC subnet - it's private-subnet
  # traffic's route OUT, so it needs its own direct path to the internet.
  subnet_id = aws_subnet.public[count.index].id
  tags      = merge(var.tags, { Name = "cadence-${var.environment}-nat-${count.index}" })

  depends_on = [aws_internet_gateway.this]
}

# ---- private subnets: RDS, ECS tasks ----

resource "aws_subnet" "private" {
  count             = var.az_count
  vpc_id            = aws_vpc.this.id
  cidr_block        = local.private_subnet_cidrs[count.index]
  availability_zone = local.azs[count.index]

  tags = merge(var.tags, { Name = "cadence-${var.environment}-private-${local.azs[count.index]}" })
}

# One route table per AZ so that, if a second NAT Gateway is ever added
# (single_nat_gateway = false), each AZ's private traffic can route
# through its own AZ's NAT Gateway rather than crossing AZs.
resource "aws_route_table" "private" {
  count  = var.az_count
  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = "cadence-${var.environment}-private-rt-${local.azs[count.index]}" })
}

resource "aws_route" "private_internet" {
  count                  = var.az_count
  route_table_id         = aws_route_table.private[count.index].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[count.index % local.nat_gateway_count].id
}

resource "aws_route_table_association" "private" {
  count          = var.az_count
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[count.index].id
}
